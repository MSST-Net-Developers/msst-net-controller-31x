plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Maven modules the apps ship, with the licenses their POMs declare; read by
// scripts/third-party-licenses.py to write THIRD_PARTY_LICENSES.txt.
tasks.register("thirdPartyModules") {
    description = "Lists the Maven modules the apps ship, with their POM licenses"
    notCompatibleWithConfigurationCache("resolves other projects' configurations")
    val out = layout.buildDirectory.file("third-party-modules.tsv")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val shipped = mapOf(
            ":desktopApp" to "runtimeClasspath",
            ":androidApp" to "releaseRuntimeClasspath",
            ":webApp" to "wasmJsRuntimeClasspath",
        )
        val modules = sortedSetOf<String>()
        shipped.forEach { (path, conf) ->
            project(path).configurations.getByName(conf).incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .forEach { modules += "${it.group}:${it.module}:${it.version}" }
        }
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        fun pom(coord: String): org.w3c.dom.Element {
            val (group, module, version) = coord.split(":")
            val file = dependencies.createArtifactResolutionQuery()
                .forModule(group, module, version)
                .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
                .execute().resolvedComponents.single()
                .getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().single().file
            return xml.newDocumentBuilder().parse(file).documentElement
        }
        fun org.w3c.dom.Element.child(name: String) =
            (0 until childNodes.length).map { childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == name }
        // A POM without <licenses> inherits them from its parent.
        fun licenses(coord: String): List<String> {
            val root = pom(coord)
            val declared = root.child("licenses").flatMap { it.child("license") }.map { l ->
                listOf("name", "url").joinToString("|") { n -> l.child(n).firstOrNull()?.textContent?.trim().orEmpty() }
            }
            if (declared.isNotEmpty()) return declared
            val parent = root.child("parent").firstOrNull() ?: return emptyList()
            return licenses(listOf("groupId", "artifactId", "version").joinToString(":") { parent.child(it).first().textContent.trim() })
        }
        out.get().asFile.writeText(modules.joinToString("") { "$it\t${licenses(it).joinToString("\t")}\n" })
    }
}
