# Fork CI only (see FAST_R8 in build.gradle.kts): shrink unused code and
# resources, but skip R8's optimization passes to cut build time.
-dontoptimize
