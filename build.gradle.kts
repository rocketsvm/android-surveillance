plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The source tree lives in a cloud-synced folder; build outputs are redirected out of it
// when -Psurveilcam.buildRoot=<dir> is given (see tools/gw.sh). Without it, the usual build/ dirs are used.
providers.gradleProperty("surveilcam.buildRoot").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
