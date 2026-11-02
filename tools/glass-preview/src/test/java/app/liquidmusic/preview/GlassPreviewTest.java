/*
 * LiquidMusic - Liquid Glass for Apple Music (patches compatible with Morphe).
 * Licensed under the GNU General Public License v3.0, see LICENSE.
 */
package app.liquidmusic.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the floating tab bar and the mini player of Apple Music 7.0 over album covers, with
 * Apple's blur and with the glass, and checks the glass the way it failed on a phone in 1.0.1.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-xxhdpi")
public class GlassPreviewTest {

    private Activity activity;
    private BlurView tabBarGlass;
    private BlurView miniPlayerGlass;
    private FrameLayout tabBar;
    private FrameLayout miniPlayer;

    @After
    public void reset() {
        BlurView.patched = true;
    }

    @Test
    public void appleOriginal() throws Exception {
        BlurView.patched = false;
        buildScene();
        render("apple-original");
    }

    @Test
    public void liquidGlass() throws Exception {
        buildScene();
        Bitmap glass = render("liquid-glass");
        assertTrue("the tab bar draws the glass", tabBarGlass.drewGlass);
        assertTrue("the mini player draws the glass", miniPlayerGlass.drewGlass);

        // The title runs under the top of the tab bar. Through the glass it must be blurred: in
        // 1.0.1 the glass was transparent and the sharp text showed through.
        int step = sharpestStep(glass, tabBar, dp(6));
        System.out.println("Sharpest step under the top of the tab bar: " + step);
        assertTrue("the content under the glass is blurred (step " + step + ")", step < 24);

        // Apple sets its material again whenever the colors of the player change.
        for (FrameLayout view : new FrameLayout[]{tabBar, miniPlayer}) {
            LayerDrawable layers = (LayerDrawable) view.getBackground();
            layers.setDrawableByLayerId(R.id.material_layer, activity.getDrawable(R.drawable.layer_apple_material));
            layers.findDrawableByLayerId(R.id.artwork_color_scheme_layer).mutate().setAlpha(255);
            view.invalidate();
        }
        render("liquid-glass-after-color-change");
        for (FrameLayout view : new FrameLayout[]{tabBar, miniPlayer}) {
            LayerDrawable layers = (LayerDrawable) view.getBackground();
            assertEquals("material layer faded to 40%", 102, layers.findDrawableByLayerId(R.id.material_layer).getAlpha());
            assertEquals("artwork layer faded to 40%", 102, layers.findDrawableByLayerId(R.id.artwork_color_scheme_layer).getAlpha());
        }
    }

    // region Scene

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

    private void buildScene() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        FrameLayout root = new FrameLayout(activity);
        BlurTarget target = new BlurTarget(activity);
        target.setId(R.id.blur_target);
        target.addView(content(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(target, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        miniPlayerGlass = glass(R.id.mini_player_blur);
        miniPlayer = material(R.id.mini_player_content);
        TextView song = label("Glass Hearts   ▶  ▶▶", Color.WHITE, 15);
        song.setPadding(dp(20), 0, dp(20), 0);
        song.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.addView(song, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(miniPlayerGlass, pill(56, 323));
        root.addView(miniPlayer, pill(56, 323));

        tabBarGlass = glass(R.id.bottom_nav_blur);
        tabBar = material(R.id.bottom_nav_view);
        LinearLayout items = new LinearLayout(activity);
        for (String item : new String[]{"Home", "New", "Radio", "Library", "Search"}) {
            TextView tab = label(item, item.equals("Home") ? 0xFFFA2D48 : Color.WHITE, 11);
            tab.setGravity(Gravity.CENTER);
            items.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        tabBar.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabBarGlass, pill(64, 247));
        root.addView(tabBar, pill(64, 247));

        activity.setContentView(root);
        ColorDrawable windowBackground = (ColorDrawable) activity.getWindow().getDecorView().getBackground();
        tabBarGlass.setupWith(target, windowBackground);
        miniPlayerGlass.setupWith(target, windowBackground);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private View content() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(440), 0, 0);
        column.addView(label("Recently Played", Color.WHITE, 26));
        column.addView(covers(new int[][]{{0xFFFF2D55, 0xFFFF9F0A}, {0xFF5E5CE6, 0xFF0A84FF}, {0xFF30D158, 0xFFFFD60A}}));
        TextView title = label("New Releases", Color.WHITE, 30);
        title.setPadding(0, dp(24), 0, 0);
        column.addView(title);
        column.addView(covers(new int[][]{{0xFFBF5AF2, 0xFFFF375F}, {0xFFFFD60A, 0xFFFF453A}, {0xFF64D2FF, 0xFF5E5CE6}}));
        return column;
    }

    private View covers(int[][] gradients) {
        LinearLayout row = new LinearLayout(activity);
        for (int[] colors : gradients) {
            View cover = new View(activity);
            GradientDrawable artwork = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
            artwork.setCornerRadius(dp(10));
            cover.setBackground(artwork);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(150), dp(150));
            params.setMargins(0, dp(12), dp(12), 0);
            row.addView(cover, params);
        }
        return row;
    }

    private TextView label(String text, int color, float sizeSp) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(sizeSp);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMaxLines(1);
        return label;
    }

    /** A BlurView as in fragment_music_content.xml. */
    private BlurView glass(int id) {
        BlurView view = new BlurView(activity);
        view.setId(id);
        view.setBackgroundResource(R.drawable.bg_corner_radius_pill);
        view.setClipToOutline(true);
        return view;
    }

    /** The view drawn over the BlurView, with Apple's translucent material. */
    private FrameLayout material(int id) {
        FrameLayout view = new FrameLayout(activity);
        view.setId(id);
        view.setBackgroundResource(R.drawable.bg_apple_material);
        return view;
    }

    private FrameLayout.LayoutParams pill(int heightDp, int bottomDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp), Gravity.BOTTOM);
        params.setMargins(dp(16), 0, dp(16), dp(bottomDp));
        return params;
    }

    // endregion

    private Bitmap render(String name) throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        int[] result = {-1};
        PixelCopy.request(activity.getWindow(), bitmap, copy -> result[0] = copy, new Handler(Looper.getMainLooper()));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(PixelCopy.SUCCESS, result[0]);

        File directory = new File(System.getProperty("preview.out"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return bitmap;
    }

    /** Largest luminance difference between neighbouring pixels on a row inside the view. */
    private static int sharpestStep(Bitmap bitmap, View view, int fromTop) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        int y = location[1] + fromTop;
        int start = location[0] + view.getHeight() / 2;
        int end = location[0] + view.getWidth() - view.getHeight() / 2;
        int sharpest = 0;
        int previous = luminance(bitmap.getPixel(start, y));
        for (int x = start + 1; x < end; x++) {
            int current = luminance(bitmap.getPixel(x, y));
            sharpest = Math.max(sharpest, Math.abs(current - previous));
            previous = current;
        }
        return sharpest;
    }

    private static int luminance(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }
}
