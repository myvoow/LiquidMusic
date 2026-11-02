/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 *
 * The effect chain (vibrancy -> blur -> lens) and the highlight parameters follow
 * Backdrop by Kyant (Apache License 2.0): https://github.com/Kyant0/AndroidLiquidGlass
 */
package app.liquidmusic.extension;

import android.annotation.TargetApi;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;

/**
 * Draws one Liquid Glass surface in place of a BlurView.
 * <p>
 * The content behind the surface is not captured again: BlurTarget already records it in a
 * RenderNode on every frame, so this renderer only references that node, offset to the position
 * of the surface, in a node of its own with a padding of one blur radius. The effect chain of that
 * node is, as in Backdrop: saturation (vibrancy), then blur, then the AGSL lens that bends the
 * edges (Android 13+). The result is clipped to the shape of the surface, and a specular rim is
 * drawn over it.
 */
@TargetApi(Build.VERSION_CODES.S)
final class GlassRenderer implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

    /** Layers of Apple's bg_apple_material drawable, made more transparent so the glass shows. */
    private static final String[] TINT_LAYER_NAMES = {"material_layer", "artwork_color_scheme_layer"};

    private final WeakReference<View> hostRef;
    private final LiquidGlassPatch.Surface surface;

    private final RenderNode node = new RenderNode("LiquidGlass");
    private final int[] hostLocation = new int[2];
    private final int[] sourceLocation = new int[2];
    private final RectF bounds = new RectF();
    private final Path shape = new Path();
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private WeakReference<View> sourceRef = new WeakReference<>(null);
    private RenderNode sourceNode;
    private float drawnOffsetX = Float.NaN;
    private float drawnOffsetY = Float.NaN;
    private boolean listening;

    // Effect cache, rebuilt only when the geometry changes.
    private RenderEffect effect;
    private int effectWidth = -1;
    private int effectHeight = -1;
    private int effectPadding = -1;
    private float effectCorner = -1f;

    private RuntimeShader highlightShader;
    private float highlightMaskRadius = -1f;

    private WeakReference<View> tintedViewRef = new WeakReference<>(null);
    private WeakReference<Drawable> tintedDrawableRef = new WeakReference<>(null);

    GlassRenderer(View host, LiquidGlassPatch.Surface surface) {
        this.hostRef = new WeakReference<>(host);
        this.surface = surface;
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setBlendMode(BlendMode.PLUS);
        host.addOnAttachStateChangeListener(this);
    }

    /**
     * @return true if the glass was drawn, false to let BlurView draw its original blur.
     */
    boolean draw(View host, Canvas canvas) {
        final int width = host.getWidth();
        final int height = host.getHeight();
        if (width <= 0 || height <= 0) return false;

        View source = resolveSource(host);
        if (source == null) return false;
        startListening(host);

        host.getLocationInWindow(hostLocation);
        source.getLocationInWindow(sourceLocation);
        final float offsetX = hostLocation[0] - sourceLocation[0];
        final float offsetY = hostLocation[1] - sourceLocation[1];
        drawnOffsetX = offsetX;
        drawnOffsetY = offsetY;

        final float density = host.getResources().getDisplayMetrics().density;
        final float blurRadius = Math.max(0, LiquidGlassPatch.blurRadiusDp()) * density;
        final int padding = (int) Math.ceil(blurRadius);
        final float corner = cornerRadius(host, width, height);

        // Reference the content behind the surface, including one blur radius around it.
        node.setPosition(0, 0, width + 2 * padding, height + 2 * padding);
        RecordingCanvas recording = node.beginRecording();
        try {
            recording.translate(padding - offsetX, padding - offsetY);
            recording.drawRenderNode(sourceNode);
        } finally {
            node.endRecording();
        }
        node.setRenderEffect(effect(width, height, padding, corner, density, blurRadius));

        bounds.set(0, 0, width, height);
        shape.rewind();
        shape.addRoundRect(bounds, corner, corner, Path.Direction.CW);

        final int save = canvas.save();
        canvas.clipPath(shape);
        canvas.translate(-padding, -padding);
        canvas.drawRenderNode(node);
        canvas.restoreToCount(save);

        drawHighlight(canvas, width, height, corner, density);
        lightenTint(host);
        return true;
    }

    private View resolveSource(View host) {
        View source = sourceRef.get();
        if (source != null && source.isAttachedToWindow() && sourceNode != null) return source;

        source = BlurViewAccess.findBlurTarget(host);
        if (source == null) return null;
        Object recorded = BlurViewAccess.recordedNodeOf(source);
        if (!(recorded instanceof RenderNode)) return null;

        sourceRef = new WeakReference<>(source);
        sourceNode = (RenderNode) recorded;
        return source;
    }

    private RenderEffect effect(int width, int height, int padding, float corner, float density, float blurRadius) {
        if (effect != null && width == effectWidth && height == effectHeight
                && padding == effectPadding && corner == effectCorner) {
            return effect;
        }

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(Math.max(0, LiquidGlassPatch.saturationPercent()) / 100f);
        RenderEffect chain = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));

        if (blurRadius > 0f) {
            chain = RenderEffect.createBlurEffect(blurRadius, blurRadius, chain, Shader.TileMode.CLAMP);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RenderEffect lens = lens(width, height, padding, corner, density);
            if (lens != null) chain = RenderEffect.createChainEffect(lens, chain);
        }

        effect = chain;
        effectWidth = width;
        effectHeight = height;
        effectPadding = padding;
        effectCorner = corner;
        return chain;
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static RenderEffect lens(int width, int height, int padding, float corner, float density) {
        final float refractionHeight = Math.min(
                Math.max(0, LiquidGlassPatch.refractionHeightDp()) * density,
                Math.min(width, height) / 2f
        );
        final float refractionAmount = Math.max(0, LiquidGlassPatch.refractionAmountDp()) * density;
        if (refractionHeight <= 0f || refractionAmount <= 0f) return null;

        final boolean dispersion = LiquidGlassPatch.chromaticAberration();
        RuntimeShader shader = new RuntimeShader(
                dispersion ? GlassShaders.REFRACTION_WITH_DISPERSION : GlassShaders.REFRACTION
        );
        shader.setFloatUniform("size", width, height);
        shader.setFloatUniform("offset", -padding, -padding);
        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner);
        shader.setFloatUniform("refractionHeight", refractionHeight);
        shader.setFloatUniform("refractionAmount", -refractionAmount);
        shader.setFloatUniform("depthEffect", 0f);
        if (dispersion) shader.setFloatUniform("chromaticAberration", 1f);
        return RenderEffect.createRuntimeShaderEffect(shader, "content");
    }

    /** Backdrop's Highlight.Default: a 0.5dp rim, white at 50%, added with BlendMode.PLUS. */
    private void drawHighlight(Canvas canvas, int width, int height, float corner, float density) {
        final int alpha = clamp(LiquidGlassPatch.highlightPercent(), 0, 100);
        if (alpha == 0) return;

        highlightPaint.setStrokeWidth((float) Math.ceil(0.5f * density) * 2f);
        final float maskRadius = 0.25f * density;
        if (maskRadius != highlightMaskRadius) {
            highlightPaint.setMaskFilter(new BlurMaskFilter(maskRadius, BlurMaskFilter.Blur.NORMAL));
            highlightMaskRadius = maskRadius;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            highlightPaint.setShader(highlightShader(width, height, corner));
            highlightPaint.setColor(Color.argb(Math.round(0.5f * 255 * alpha / 100f), 255, 255, 255));
        } else {
            highlightPaint.setShader(null);
            highlightPaint.setColor(Color.argb(Math.round(0.38f * 255 * alpha / 100f), 255, 255, 255));
        }

        final int save = canvas.save();
        canvas.clipPath(shape);
        canvas.drawPath(shape, highlightPaint);
        canvas.restoreToCount(save);
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private RuntimeShader highlightShader(int width, int height, float corner) {
        if (highlightShader == null) highlightShader = new RuntimeShader(GlassShaders.HIGHLIGHT);
        highlightShader.setFloatUniform("size", width, height);
        highlightShader.setFloatUniform("cornerRadii", corner, corner, corner, corner);
        highlightShader.setColorUniform("color", Color.WHITE);
        highlightShader.setFloatUniform("angle", (float) Math.toRadians(45));
        highlightShader.setFloatUniform("falloff", 1f);
        return highlightShader;
    }

    /**
     * The tab bar and the mini player draw Apple's translucent material (bg_apple_material,
     * 55 to 60% opaque) over the blur. Its layers are faded so the glass shows through.
     */
    private void lightenTint(View host) {
        final String contentName = surface.contentIdName;
        final int percent = clamp(LiquidGlassPatch.tintOpacityPercent(), 0, 100);
        if (contentName == null || percent >= 100) return;

        View content = tintedViewRef.get();
        if (content == null || !content.isAttachedToWindow()) {
            int id = LiquidGlassPatch.idOf(host, contentName);
            if (id == 0) return;
            content = host.getRootView().findViewById(id);
            if (content == null) return;
            tintedViewRef = new WeakReference<>(content);
        }

        Drawable background = content.getBackground();
        if (background == null || background == tintedDrawableRef.get()) return;

        final int alpha = Math.round(255 * percent / 100f);
        Drawable mutated = background.mutate();
        boolean faded = false;
        if (mutated instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) mutated;
            for (String layerName : TINT_LAYER_NAMES) {
                int layerId = LiquidGlassPatch.idOf(host, layerName);
                Drawable layer = layerId != 0 ? layers.findDrawableByLayerId(layerId) : null;
                if (layer != null) {
                    layer.setAlpha(alpha);
                    faded = true;
                }
            }
        }
        if (!faded) mutated.setAlpha(alpha);

        tintedDrawableRef = new WeakReference<>(content.getBackground());
        content.invalidate();
    }

    private static float cornerRadius(View host, int width, int height) {
        final float max = Math.min(width, height) / 2f;
        Drawable background = host.getBackground();
        if (background instanceof GradientDrawable) {
            float radius = ((GradientDrawable) background).getCornerRadius();
            if (radius > 0f) return Math.min(radius, max);
        }
        return max;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // region Position tracking

    private void startListening(View host) {
        if (listening) return;
        host.getViewTreeObserver().addOnPreDrawListener(this);
        listening = true;
    }

    private void stopListening(View host) {
        if (!listening) return;
        host.getViewTreeObserver().removeOnPreDrawListener(this);
        listening = false;
    }

    /**
     * The content behind updates by itself (the node references BlurTarget's node), but a move
     * of the surface, such as the mini player animating, needs the offset to be recorded again.
     */
    @Override
    public boolean onPreDraw() {
        View host = hostRef.get();
        View source = sourceRef.get();
        if (host == null || source == null || !host.isShown()) return true;
        host.getLocationInWindow(hostLocation);
        source.getLocationInWindow(sourceLocation);
        if (hostLocation[0] - sourceLocation[0] != drawnOffsetX
                || hostLocation[1] - sourceLocation[1] != drawnOffsetY) {
            host.invalidate();
        }
        return true;
    }

    @Override
    public void onViewAttachedToWindow(View view) {
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        stopListening(view);
        node.discardDisplayList();
        sourceRef = new WeakReference<>(null);
        sourceNode = null;
        tintedViewRef = new WeakReference<>(null);
        tintedDrawableRef = new WeakReference<>(null);
    }

    // endregion
}
