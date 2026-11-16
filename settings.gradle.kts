System.getenv("AAPT2_FROM_MAVEN_OVERRIDE")?.takeIf { it.isNotBlank() }?.let { aapt2Path ->
    gradle.startParameter.projectProperties = gradle.startParameter.projectProperties +
        mapOf("android.aapt2FromMavenOverride" to aapt2Path)
}

apply(from = "repositories.gradle.kts")

rootProject.name = "extensions"

include(":core")

File(rootDir, "lib").eachDir { include("lib:${it.name}") }

if (System.getenv("CI") != "true") {
    loadAllIndividualExtensions()
} else {
    val chunkSize = System.getenv("CI_CHUNK_SIZE")?.toIntOrNull() ?: Int.MAX_VALUE
    val chunk = System.getenv("CI_CHUNK_NUM")?.toIntOrNull() ?: 0

    File(rootDir, "src").getChunk(chunk, chunkSize)?.forEach {
        loadIndividualExtension(it.parentFile.name, it.name)
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            loadIndividualExtension(dir.name, subdir.name)
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.getChunk(chunk: Int, chunkSize: Int): List<File>? {
    return listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> dir.listFiles()?.filter { it.isDirectory } }
        ?.flatten()
        ?.sortedBy { it.name }
        ?.chunked(chunkSize)
        ?.getOrNull(chunk)
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
