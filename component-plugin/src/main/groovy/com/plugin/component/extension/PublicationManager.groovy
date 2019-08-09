package com.plugin.component.extension

import com.plugin.component.PluginRuntime
import com.plugin.component.extension.module.Digraph
import com.plugin.component.extension.module.SourceFile
import com.plugin.component.extension.module.SourceSet
import com.plugin.component.extension.option.PublicationOption
import com.plugin.component.utils.PublicationUtil
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import com.plugin.component.Constants

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 单例依赖发布器
 */
class PublicationManager {

    private static PublicationManager sPublicationManager

    private File misDir
    private Map<String, PublicationOption> publicationManifest        //maven Id 映射 PublicationOption

    Digraph<String> dependencyGraph
    Map<String, PublicationOption> publicationDependencies

    static getInstance() {
        if (sPublicationManager == null) {
            sPublicationManager = new PublicationManager()
        }
        return sPublicationManager
    }

    /**
     * 加载 publicationManifest.xml，其用于记录依赖项目依赖管理
     * @param rootProject
     * @param misDir
     */
    void loadManifest(Project rootProject, File misDir) {
        this.misDir = misDir

        publicationManifest = new HashMap<>()
        dependencyGraph = new Digraph<String>()
        publicationDependencies = new HashMap<>()

        rootProject.gradle.buildFinished {
            PluginRuntime.resetProjectInfoScript()
            if (it.failure != null) {
                return
            }
            saveManifest()
        }

        File publicationManifest = new File(misDir, 'publicationManifest.xml')
        if (!publicationManifest.exists()) {
            return
        }

        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance()
        Document document = builderFactory.newDocumentBuilder().parse(publicationManifest)

        //搜寻所有publication节点
        NodeList publicationNodeList = document.documentElement.getElementsByTagName("publication")
        for (int i = 0; i < publicationNodeList.getLength(); i++) {

            Element publicationElement = (Element) publicationNodeList.item(i)

            PublicationOption publication = new PublicationOption()
            publication.project = publicationElement.getAttribute("project")
            publication.groupId = publicationElement.getAttribute("groupId")
            publication.artifactId = publicationElement.getAttribute("artifactId")
            publication.version = publicationElement.getAttribute("version")
            if (publication.version == "") publication.version = null
            publication.invalid = Boolean.valueOf(publicationElement.getAttribute("invalid"))

            //如果有效
            if (!publication.invalid) {
                NodeList sourceSetNodeList = publicationElement.getElementsByTagName("sourceSet")
                Element sourceSetElement = (Element) sourceSetNodeList.item(0)
                SourceSet sourceSet = new SourceSet()
                sourceSet.path = sourceSetElement.getAttribute("path")
                sourceSet.lastModifiedSourceFile = new HashMap<>()
                NodeList fileNodeList = sourceSetElement.getElementsByTagName("file")
                for (int k = 0; k < fileNodeList.getLength(); k++) {
                    Element fileElement = (Element) fileNodeList.item(k)
                    SourceFile sourceFile = new SourceFile()
                    sourceFile.path = fileElement.getAttribute("path")
                    sourceFile.lastModified = fileElement.getAttribute("lastModified").toLong()
                    sourceSet.lastModifiedSourceFile.put(sourceFile.path, sourceFile)
                }
                publication.misSourceSet = sourceSet
            }

            this.publicationManifest.put(publication.groupId + '-' + publication.artifactId, publication)
        }

    }

    /**
     * 保存 publicationManifest.xml
     */
    private void saveManifest() {
        if (!misDir.exists()) {
            misDir.mkdirs()
        }
        File publicationManifest = new File(misDir, 'publicationManifest.xml')

        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance()
        Document document = builderFactory.newDocumentBuilder().newDocument()
        Element manifestElement = document.createElement("manifest")
        this.publicationManifest.each {
            PublicationOption publication = it.value

            if (!publication.hit || publication.invalid) return

            Element publicationElement = document.createElement('publication')
            publicationElement.setAttribute('project', publication.project)
            publicationElement.setAttribute('groupId', publication.groupId)
            publicationElement.setAttribute('artifactId', publication.artifactId)
            publicationElement.setAttribute('version', publication.version)
            publicationElement.setAttribute('invalid', publication.invalid ? "true" : "false")

            if (!publication.invalid) {
                Element sourceSetElement = document.createElement('sourceSet')
                sourceSetElement.setAttribute('path', publication.misSourceSet.path)
                publication.misSourceSet.lastModifiedSourceFile.each {
                    SourceFile sourceFile = it.value
                    Element sourceFileElement = document.createElement('file')
                    sourceFileElement.setAttribute('path', sourceFile.path)
                    sourceFileElement.setAttribute('lastModified', sourceFile.lastModified.toString())
                    sourceSetElement.appendChild(sourceFileElement)
                }
                publicationElement.appendChild(sourceSetElement)
            }
            manifestElement.appendChild(publicationElement)
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.CDATA_SECTION_ELEMENTS, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.transform(new DOMSource(manifestElement), new StreamResult(publicationManifest))
    }

    void addDependencyGraph(PublicationOption publication) {
        def key = publication.groupId + '-' + publication.artifactId
        publicationDependencies.put(key, publication)
        dependencyGraph.add(key)
        if (publication.dependencies != null) {
            if (publication.dependencies.implementation != null) {
                publication.dependencies.implementation.each {
                    if (it instanceof String && it.startsWith(Constants.SDK_PRE)) {
                        String[] gav = PublicationUtil.filterGAV(it.replace(Constants.SDK_PRE, ''))
                        dependencyGraph.add(key, gav[0] + '-' + gav[1])
                        if (!dependencyGraph.isDag()) {
                            def component = gav[0] + ':' + gav[1] + (gav[2] == null ? (":" + gav[2]) : "")
                            throw new RuntimeException("Circular dependency between component publication '${publication.groupId}:${publication.artifactId}' and '${component}'.")
                        }
                    }
                }
            }

            if (publication.dependencies.compileOnly != null) {
                publication.dependencies.compileOnly.each {
                    if (it instanceof String && it.startsWith(Constants.SDK_PRE)) {
                        String[] gav = PublicationUtil.filterGAV(it.replace(Constants.SDK_PRE, ''))
                        dependencyGraph.add(key, gav[0] + '-' + gav[1])
                        if (!dependencyGraph.isDag()) {
                            def component = gav[0] + ':' + gav[1] + (gav[2] == null ? (":" + gav[2]) : "")
                            throw new RuntimeException("Circular dependency between component publication '${publication.groupId}:${publication.artifactId}' and '${component}'.")
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断是否修改
     * @param publication
     * @return
     */
    boolean hasModified(PublicationOption publication) {
        PublicationOption lastPublication = publicationManifest.get(publication.groupId + '-' + publication.artifactId)
        if (lastPublication == null) {
            return true
        }
        if (publication.invalid != lastPublication.invalid) {
            return true
        }
        return hasModifiedSourceSet(publication.misSourceSet, lastPublication.misSourceSet)
    }

    private boolean hasModifiedSourceSet(SourceSet sourceSet1, SourceSet sourceSet2) {
        return hasModifiedSourceFile(sourceSet1.lastModifiedSourceFile, sourceSet2.lastModifiedSourceFile)
    }

    private boolean hasModifiedSourceFile(Map<String, SourceFile> map1, Map<String, SourceFile> map2) {
        if (map1.size() != map2.size()) {
            return true
        }
        for (Map.Entry<String, SourceFile> entry1 : map1.entrySet()) {
            SourceFile sourceFile1 = entry1.getValue()
            SourceFile sourceFile2 = map2.get(entry1.getKey())
            if (sourceFile2 == null) {
                return true
            }
            if (sourceFile1.lastModified != sourceFile2.lastModified) {
                return true
            }
        }
        return false
    }

    void addPublication(PublicationOption publication) {
        publicationManifest.put(publication.groupId + '-' + publication.artifactId, publication)
    }

    PublicationOption getPublication(String groupId, String artifactId) {
        return publicationManifest.get(groupId + '-' + artifactId)
    }

    PublicationOption getPublicationByKey(String key) {
        return publicationManifest.get(key)
    }

    List<PublicationOption> getPublicationByProject(Project project) {
        String displayName = project.getDisplayName()
        String projectName = displayName.substring(displayName.indexOf("'") + 1, displayName.lastIndexOf("'"))

        List<PublicationOption> publications = new ArrayList<>()
        publicationManifest.each {
            if (projectName == it.value.project) {
                publications.add(it.value)
            }
        }
        return publications
    }

    void hitPublication(PublicationOption publication) {
        PublicationOption existsPublication = publicationManifest.get(publication.groupId + '-' + publication.artifactId)
        if (existsPublication == null) return

        if (existsPublication.hit) {
            validPublication(publication, existsPublication)
        } else {
            existsPublication.hit = true
        }
    }

    private void validPublication(PublicationOption publication, PublicationOption existsPublication) {
        if (publication.project != existsPublication.project) {
            throw new GradleException("Already exists publication " + existsPublication.groupId + ":" + existsPublication.artifactId + " in project '${existsPublication.project}'.")
        }
    }

}