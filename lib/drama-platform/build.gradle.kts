plugins {
    id("lib-android")
}

dependencies {
    api(project(":lib:stream-proxy"))
    testImplementation(libs.nanohttpd)
}
