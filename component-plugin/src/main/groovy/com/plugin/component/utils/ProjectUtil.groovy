package com.plugin.component.utils

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.plugin.component.Constants
import com.plugin.component.Logger
import com.plugin.component.Runtimes
import com.plugin.component.extension.module.ProjectInfo
import com.plugin.component.extension.option.debug.DebugConfiguration
import org.gradle.api.Project

class ProjectUtil {

    static Project getProject(Project root, String childName) {
        def result = null
        root.allprojects.each {
            if (it.name == getComponentValue(childName)) {
                result = it
            }
        }
        return result
    }

    static String getProjectName(String projectName) {
        if (projectName == null || projectName.isEmpty()) {
            return String
        }
        return projectName.replace(":", "")
    }

    static String getProjectName(Project project) {
        if (project == null) {
            return null
        }
        return project.name.replace(":", "")
    }

    static boolean isProjectSame(String name1, String name2) {
        return name1 == name2
    }

    /**
     * 解析include exclude
     * @param modules
     * @return
     */
    static Set<String> getModuleName(String modules) {
        Set<String> result = new HashSet<>()
        if (modules != null && !modules.isEmpty()) {
            String[] strings = modules.split(",")
            if (strings != null && strings.length > 0) {
                for (String string : strings) {
                    result.add(getProjectName(string))
                }
            }
        }
        return result
    }

    /**
     * example: 兼容 component(:library) 和 component(library)
     * @param componentValue
     * @return
     */
    static String getComponentValue(String componentValue) {
        if (componentValue == null || componentValue.isEmpty()) {
            return componentValue
        }
        if (componentValue.startsWith(":")) {
            componentValue = componentValue.substring(1, componentValue.length())
        }
        return componentValue
    }

    static List<String> getTasks(Project project) {
        return project.gradle.getStartParameter().taskNames
    }

    static String getTaskName(Project project) {
        return project.gradle.startParameter.taskNames.toString()
    }


    static modifySourceSets(ProjectInfo projectInfo) {
        Project project = projectInfo.project
        BaseExtension baseExtension = project.extensions.getByName(Constants.ANDROID)
        modifySourceSets(baseExtension, Constants.MAIN)
        if (baseExtension instanceof AppExtension) {
            AppExtension appExtension = (AppExtension) baseExtension
            appExtension.getApplicationVariants().each {
                modifySourceSets(baseExtension, it.buildType.name)
                it.productFlavors.each {
                    modifySourceSets(baseExtension, it.name)
                }
                if (it.productFlavors.size() >= 1) {
                    if (it.productFlavors.size() > 1) {
                        modifySourceSets(baseExtension, it.flavorName)
                    }
                    modifySourceSets(baseExtension, it.name)
                }
            }
        } else if (baseExtension instanceof LibraryExtension) {
            LibraryExtension libraryExtension = (LibraryExtension) baseExtension
            libraryExtension.getLibraryVariants().each {
                modifySourceSets(baseExtension, it.buildType.name)
                it.productFlavors.each {
                    modifySourceSets(baseExtension, it.name)
                }
                if (it.productFlavors.size() >= 1) {
                    if (it.productFlavors.size() > 1) {
                        modifySourceSets(baseExtension, it.flavorName)
                    }
                    modifySourceSets(baseExtension, it.name)
                }
            }
        }
    }

    static modifySourceSets(BaseExtension baseExtension, String name) {
        def obj = baseExtension.sourceSets.getByName(name)
        obj.java.srcDirs.each {
            obj.aidl.srcDirs(it.absolutePath.replace(Constants.JAVA, Constants.SDK))
        }
    }

    static modifyDebugSets(Project root, ProjectInfo projectInfo) {
        if (root == null || projectInfo == null) {
            return
        }
        Project project = projectInfo.project
        BaseExtension baseExtension = project.extensions.getByName(Constants.ANDROID)
        def objMain = baseExtension.sourceSets.getByName(Constants.MAIN)
        def objAndroidTest = baseExtension.sourceSets.getByName("androidTest")
        def configurations = Runtimes.getDebugConfigurations()
        if (!configurations.isEmpty()) {
            Logger.buildOutput("hasDebugOptions", true)
            for (DebugConfiguration configuration : configurations) {
                def componentName = configuration.name
                def file = new File(project.projectDir, "src/main/" + componentName + "/")
                if (file == null || !file.exists()) {
                    Logger.buildOutput("skip component[" + componentName + "] directory does not exist!")
                    continue
                }
                if (isProjectSame(componentName, Runtimes.getDebugTargetName())) {
                    Logger.buildOutput("add dir[" + componentName + "] sourceSets to Main")
                    def applicationId = "com.component.debug." + componentName
                    Logger.buildOutput("修改前 debug apk applicationId", baseExtension.defaultConfig.applicationId)
                    Logger.buildOutput("修改后 debug apk applicationId", applicationId)
                    baseExtension.defaultConfig.setApplicationId(applicationId)
                    objMain.java.srcDir("src/main/" + componentName + "/java")
                    objMain.res.srcDir("src/main/" + componentName + "/res")
                    objMain.assets.srcDir("src/main/" + componentName + "/assets")
                    objMain.jniLibs.srcDir("src/main/" + componentName + "/jniLibs")
                    objMain.manifest.srcFile("src/main/" + componentName + "/AndroidManifest.xml")
                    if (configuration.dependencies.implementation != null) {
                        Logger.buildOutput("add dependencies ==> ")
                        projectInfo.project.dependencies {
                            configuration.dependencies.implementation.each {
                                def dependency = it
                                if (it instanceof String && it.startsWith(Constants.DEBUG_COMPONENT_PRE)) {
                                    dependency = PublicationUtil.parseComponent(projectInfo, it.replace(Constants.DEBUG_COMPONENT_PRE, ""))
                                }
                                Logger.buildOutput("implementation " + dependency)
                                implementation dependency
                            }
                        }
                    }

                } else {
                    Logger.buildOutput("add component[" + componentName + "] sourceSets to AndroidTest")
                    objAndroidTest.java.srcDir("src/main/" + componentName + "/java")
                    objAndroidTest.res.srcDir("src/main/" + componentName + "/res")
                    objAndroidTest.assets.srcDir("src/main/" + componentName + "/assets")
                    objAndroidTest.jniLibs.srcDir("src/main/" + componentName + "/jniLibs")
                    objAndroidTest.manifest.srcFile("src/main/" + componentName + "/AndroidManifest.xml")
                }
            }
        } else {
            Logger.buildOutput("hasDebugOptions", false)
        }
        Logger.buildOutput("DebugModule[" + project.name + "]" + "Main sourceSets: ")
        Logger.buildOutput("java", sourceSetDirToString(objMain.java.srcDirs))
        Logger.buildOutput("res", sourceSetDirToString(objMain.res.srcDirs))
        Logger.buildOutput("assets", sourceSetDirToString(objMain.assets.srcDirs))
        Logger.buildOutput("jniLibs", sourceSetDirToString(objMain.jniLibs.srcDirs))
        Logger.buildOutput("manifest", objAndroidTest.manifest.srcFile.path)
        Logger.buildOutput("DebugModule[" + project.name + "]" + "AndroidTest sourceSets: ")
        Logger.buildOutput("java", sourceSetDirToString(objAndroidTest.java.srcDirs))
        Logger.buildOutput("res", sourceSetDirToString(objAndroidTest.res.srcDirs))
        Logger.buildOutput("assets", sourceSetDirToString(objAndroidTest.assets.srcDirs))
        Logger.buildOutput("jniLibs", sourceSetDirToString(objAndroidTest.jniLibs.srcDirs))
        Logger.buildOutput("manifest", objAndroidTest.manifest.srcFile.path)
    }

    private static String sourceSetDirToString(Set<File> set) {
        StringBuilder stringBuilder = new StringBuilder()
        if (set != null && !set.isEmpty()) {
            if(set.size() == 1){
                for (File file : set) {
                    stringBuilder.append("[ " + file.path + " ]")
                }
            }else{
                stringBuilder.append("[ \n")
                for (File file : set) {
                    stringBuilder.append("                      " + file.path + "\n")
                }
                stringBuilder.append("                 ]")
            }
        }
        return stringBuilder.toString()
    }

    /**
     * 返回项目设置的android jar路径
     * @param project
     * @param compileSdkVersion
     * @return
     */
    static String getAndroidJarPath(Project project, int compileSdkVersion) {
        def androidHome
        def env = System.getenv()

        //获取android jar路径
        if (env[Constants.ANDROID_HOME] != null) {
            androidHome = env[Constants.ANDROID_HOME]
        } else {
            def localProperties = new File(project.rootProject.rootDir, Constants.LOCAL_PROPERTIES)
            if (localProperties.exists()) {
                Properties properties = new Properties()
                localProperties.withInputStream { instr ->
                    properties.load(instr)
                }
                androidHome = properties.getProperty('sdk.dir')
            }
        }

        if (compileSdkVersion == 0) {
            throw new RuntimeException("component compileSdkVersion is not specified.")
        }

        def androidJar = new File(androidHome, "/platforms/android-${compileSdkVersion}/android.jar")
        if (!androidJar.exists()) {
            throw new RuntimeException("Failed to find Platform SDK with path: platforms;android-$compileSdkVersion")
        }
        return androidJar.absolutePath
    }
}
