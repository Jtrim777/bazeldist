package com.vaticle.bazel.distribution.maven

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.system.exitProcess

@Command(name = "maven-deployer", mixinStandardHelpOptions = true)
class Deployer : Callable<Unit> {

    @Option(names = ["--artifact"])
    lateinit var jarFile: File

    @Option(names = ["--sources"])
    var srcsFile: File? = null

    @Option(names = ["--uber-jar"])
    var uberJarFile: File? = null

    @Option(names = ["--pom"])
    lateinit var pomFile: File

    @Option(names = ["--release"])
    lateinit var releaseURL: String

    @Option(names = ["--snapshot"])
    lateinit var snapshotURL: String

    @Option(names = ["--is-snapshot"])
    var isSnapshot: Boolean = false

    @Option(names = ["--do-sign"])
    var doSign: Boolean = false

    private val snapshotVersionFormat: Regex = Regex("^[0-9|a-f|A-F]{40}$|.*-SNAPSHOT$")
    private val releaseVersionFormat: Regex = Regex("^[0-9]+.[0-9]+.[0-9]+(-[a-zA-Z0-9]+)*$")

    override fun call() {
        val pom = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(pomFile)
                .documentElement

        val group = pom.getElementsByTagName("groupId").item(0).textContent
        val artifact = pom.getElementsByTagName("artifactId").item(0).textContent
        val version = pom.getElementsByTagName("version").item(0).textContent

        if (isSnapshot && !version.matches(snapshotVersionFormat)) {
            throw java.lang.IllegalArgumentException("Invalid format for snapshot version")
        } else if (!isSnapshot && !version.matches(releaseVersionFormat)) {
            throw java.lang.IllegalArgumentException("Invalid format for release version")
        }

        val repo = if (isSnapshot) snapshotURL else releaseURL

        val rootPath = group.replace('.', '/') + "/" + artifact
        val fileBasePath = "$rootPath/$version/$artifact-$version"

        fun target(suffix: String): String = "$fileBasePath/$suffix"

        val metadoc = downloadMetadata(repo, rootPath) ?: newMetadataDoc(group, artifact, version)
        val newMetadata = updateMetadata(metadoc, version)

        val transformer = TransformerFactory.newInstance().newTransformer()
        val msource = DOMSource(newMetadata)
        val metaTmp = Files.createTempFile("deploymaven", ".xml")
        val resultStream = StreamResult(Files.newOutputStream(metaTmp))
        transformer.transform(msource, resultStream)

        if (doSign) {
            println("\u001b[31mArtifact GPG signing is not yet supported\u001b[0m")
        }

        uploadFile(repo, rootPath, metaTmp, "maven-metadata.xml")

        uploadFile(repo, rootPath, pomFile, target(".pom"))

        uploadJar(repo, rootPath, jarFile, target(".jar"))

        srcsFile?.let {
            uploadJar(repo, rootPath, it, target("-sources.jar"))
        }

        uberJarFile?.let {
            uploadJar(repo, rootPath, it, target("-all.jar"))
        }
    }

    private fun uploadJar(repo: String, root: String, data: File, targetPath: String) {
        uploadFile(repo, root, data, targetPath)

        uploadFile(repo, root, computeMD5(data), "$targetPath.md5")
        uploadFile(repo, root, computeSHA1(data), "$targetPath.sha1")

        //TODO: Add signing support
    }

    private fun uploadFile(repo: String, root: String, data: File, targetPath: String) {
        val pth = Paths.get(data.absolutePath)
        uploadFile(repo, root, pth, targetPath)
    }

    private fun uploadFile(repo: String, root: String, data: Path, targetPath: String) {
        val result = doUploadFile(repo, root, data, targetPath)

        if (!(200..299).contains(result)) {
            throw IOException("Failed to upload file $targetPath: Response from server was $result")
        }
    }

    private fun doUploadFile(repo: String, root: String, data: Path, targetPath: String): Int {
        val url = URL("https", repo, "$root/$targetPath")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.outputStream.use {
            Files.copy(data, it)
            it.flush()
        }

        return connection.responseCode
    }

    private fun computeMD5(file: File): Path {
        val md = MessageDigest.getInstance("MD5")
        val result = md.digest(Files.readAllBytes(Paths.get(file.absolutePath)))

        val tmp = Files.createTempFile("deploymaven", ".md5")
        Files.write(tmp, result)

        return tmp
    }

    private fun computeSHA1(file: File): Path {
        val md = MessageDigest.getInstance("SHA1")
        val result = md.digest(Files.readAllBytes(Paths.get(file.absolutePath)))

        val tmp = Files.createTempFile("deploymaven", ".sha1")
        Files.write(tmp, result)

        return tmp
    }

    private fun nodeListToList(nl: NodeList): List<Node> {
        val len = nl.length

        return (0 until len).toList().map { nl.item(it) }
    }

    private fun searchText(path: String, factory: XPathFactory, doc: Document): String {
        val pathObj = factory.newXPath().compile(path)
        return pathObj.evaluate(doc, XPathConstants.STRING) as String
    }

    private fun searchElement(path: String, factory: XPathFactory, doc: Document): Element {
        val pathObj = factory.newXPath().compile(path)
        return pathObj.evaluate(doc, XPathConstants.NODE) as Element
    }

    private fun searchList(path: String, factory: XPathFactory, doc: Document): List<Node> {
        val pathObj = factory.newXPath().compile(path)
        return nodeListToList(pathObj.evaluate(doc, XPathConstants.NODESET) as NodeList)
    }

    private fun downloadMetadata(repo: String, root: String): Document? {
        val metadataUrl = URL("https", "maven.jtrim777.dev", "$repo/$root/maven-metadata.xml")

        val builder = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()

        return try { metadataUrl.openStream().use {
            builder.parse(it)
        }} catch (e: java.lang.Exception) { null }
    }

    private fun newMetadataDoc(group: String, artifact: String, version: String): Document {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

        val rootElement = doc.createElement("metadata")
        doc.appendChild(rootElement)

        val grpElem = doc.createElement("groupId")
        grpElem.appendChild(doc.createTextNode(group))
        rootElement.appendChild(grpElem)

        val artElem = doc.createElement("artifactId")
        artElem.appendChild(doc.createTextNode(artifact))
        rootElement.appendChild(artElem)

        val vsnElem = doc.createElement("versioning")

        val vsnLatest = doc.createElement("latest")
        vsnLatest.appendChild(doc.createTextNode(version))
        vsnElem.appendChild(vsnLatest)

        if (!isSnapshot) {
            val vsnRelease = doc.createElement("release")
            vsnRelease.appendChild(doc.createTextNode(version))
            vsnElem.appendChild(vsnRelease)
        }

        val vsnLstElem = doc.createElement("versions")
        val vsnLstEntryElem = doc.createElement("version")
        vsnLstEntryElem.appendChild(doc.createTextNode(version))
        vsnLstElem.appendChild(vsnLstEntryElem)
        vsnElem.appendChild(vsnLstElem)

        val vsnLUElem = doc.createElement("lastUpdated")
        vsnLUElem.textContent = ""
        vsnElem.appendChild(vsnLUElem)

        rootElement.appendChild(vsnElem)

        return doc
    }

    private fun updateMetadata(metadoc: Document, currentVersion: String): Document  {
        val pathFactory = XPathFactory.newInstance()
        val versionList = searchList("/metadata/versioning/versions/version", pathFactory, metadoc)
        if (versionList.find { it.textContent == currentVersion } == null) {
            val versions = searchElement("/metadata/versioning/versions", pathFactory, metadoc)
            val ve = metadoc.createElement("version")
            ve.appendChild(metadoc.createTextNode(currentVersion))
            versions.appendChild(ve)
        }

        val versionElem = searchElement("/metadata/versioning/latest", pathFactory, metadoc)
        versionElem.textContent = currentVersion

        if (!isSnapshot) {
            val releaseElem = searchElement("/metadata/versioning/release", pathFactory, metadoc)
            releaseElem.textContent = currentVersion
        }

        val stampFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"))
        val timestamp: String = stampFormat.format(Instant.now())

        val timeElem = searchElement("/metadata/versioning/lastUpdated", pathFactory, metadoc)
        timeElem.textContent = timestamp

        return metadoc
    }
}

fun main(args: Array<String>): Unit = exitProcess(CommandLine(Deployer()).execute(*args))