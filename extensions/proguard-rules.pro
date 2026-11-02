# R8 only removes unused classes from the extensions: no optimization, no renaming,
# so the patch can call the extension methods by name.
-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.liquidmusic.** {
  *;
}
