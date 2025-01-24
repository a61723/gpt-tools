package com.github.zjh7890.gpttools.utils

import com.github.zjh7890.gpttools.services.*
import com.github.zjh7890.gpttools.services.ChatCodingService.Companion.collectFileContents
import com.github.zjh7890.gpttools.toolWindow.treePanel.ClassDependencyInfo
import com.github.zjh7890.gpttools.toolWindow.treePanel.MavenDependencyId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * @Date: 2025/1/24 18:18
 */
object DependencyUtils {
    /**
     * 将 classDependencyGraph 转换为 AppFileTree
     */
    fun buildAppFileTreeFromClassGraph(
        classDependencyGraph: Map<PsiClass, ClassDependencyInfo>
    ): AppFileTree {
        // 以 Project 为粒度分组
        val projectFileTreeMap = mutableMapOf<Project, ProjectFileTree>()

        for ((psiClass, dependencyInfo) in classDependencyGraph) {
            val vFile = psiClass.containingFile?.virtualFile ?: continue
            val project = psiClass.project

            // 先拿到或创建对应的 ProjectFileTree
            val projectFileTree = projectFileTreeMap.getOrPut(project) {
                ProjectFileTree(
                    projectName = project.name,
                    // 注意，这里不再直接把所有文件都塞进 fileTree，
                    // 而是使用 modules 和 mavenDependencies 两个列表
                    modules = mutableListOf(),
                    mavenDependencies = mutableListOf()
                )
            }

            // 判断是否是外部依赖
            val projectPath = project.basePath ?: ""
            val isExternal = !vFile.path.startsWith(projectPath)

            if (!isExternal) {
                // 本地依赖 → 找到或创建对应的 module
                val moduleName = getModuleName(project, psiClass)
                val moduleDependency = findOrCreateModule(projectFileTree.modules, moduleName)

                // 找到或创建对应的 packageDependency
                val packageName = psiClass.qualifiedName
                    ?.substringBeforeLast('.')
                    ?: "(default package)"
                val packageDependency = findOrCreatePackage(moduleDependency.packages, packageName)

                // 找到或创建 ProjectFile
                val projectFile = findOrCreateProjectFile(packageDependency.files, vFile, project)

                // 最后将当前 psiClass + usedMethods 放进 projectFile.classes
                fillProjectClassAndMethods(projectFile, psiClass, dependencyInfo)
            } else {
                // 外部依赖 → 解析 maven info
                val mavenInfo = extractMavenInfo(vFile.path)
                if (mavenInfo != null) {
                    val mavenDependency = findOrCreateMavenDependency(
                        projectFileTree.mavenDependencies,
                        mavenInfo.groupId,
                        mavenInfo.artifactId,
                        mavenInfo.version
                    )
                    // 包名
                    val packageName = psiClass.qualifiedName
                        ?.substringBeforeLast('.')
                        ?: "(default package)"
                    val packageDependency = findOrCreatePackage(mavenDependency.packages, packageName)
                    val projectFile = findOrCreateProjectFile(packageDependency.files, vFile, project, isMaven = true)

                    fillProjectClassAndMethods(projectFile, psiClass, dependencyInfo)
                }
            }
        }

        // 若某个文件里所有类均被整类使用，则设置该文件为 whole
        projectFileTreeMap.values.forEach { projectFileTree ->
            projectFileTree.modules.forEach { module ->
                module.packages.forEach { pkg ->
                    pkg.files.forEach { file ->
                        if (file.classes.isNotEmpty() && file.classes.all { it.whole }) {
                            file.whole = true
                            file.classes.clear()
                        }
                    }
                }
            }
            projectFileTree.mavenDependencies.forEach { mdep ->
                mdep.packages.forEach { pkg ->
                    pkg.files.forEach { file ->
                        if (file.classes.isNotEmpty() && file.classes.all { it.whole }) {
                            file.whole = true
                            file.classes.clear()
                        }
                    }
                }
            }
        }

        return AppFileTree(
            projectFileTrees = projectFileTreeMap.values.toMutableList()
        )
    }

    /**
     * 根据 DependenciesTreePanel 原先的逻辑，把外部依赖 jar 路径解析出 groupId, artifactId, version
     */
    private fun extractMavenInfo(path: String): MavenDependencyId? {
        val regex = ".*/repository/(.+)/([^/]+)/([^/]+)/([^/]+)\\.jar!/.*".toRegex()
        val matchResult = regex.find(path) ?: return null
        return try {
            val (groupIdPath, artifactId, version, _) = matchResult.destructured
            MavenDependencyId(
                groupId = groupIdPath.replace('/', '.'),
                artifactId = artifactId,
                version = version
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据文件相对路径(形如 /moduleA/src/...) 前缀来判断 module
     */
    private fun getModuleName(project: Project, cls: PsiClass): String {
        val vFile = cls.containingFile?.virtualFile ?: return "UnknownModule"
        val basePath = project.basePath ?: return "UnknownModule"
        val relativePath = vFile.path.removePrefix(basePath).removePrefix("/")
        return relativePath.split("/").firstOrNull() ?: "UnknownModule"
    }

    private fun findOrCreateModule(modules: MutableList<ModuleDependency>, moduleName: String): ModuleDependency {
        return modules.find { it.moduleName == moduleName } ?: ModuleDependency(moduleName).also {
            modules.add(it)
        }
    }

    private fun findOrCreateMavenDependency(
        mavenDeps: MutableList<MavenDependency>,
        groupId: String,
        artifactId: String,
        version: String
    ): MavenDependency {
        return mavenDeps.find {
            it.groupId == groupId && it.artifactId == artifactId && it.version == version
        } ?: MavenDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = version
        ).also {
            mavenDeps.add(it)
        }
    }

    private fun findOrCreatePackage(
        packages: MutableList<PackageDependency>,
        packageName: String
    ): PackageDependency {
        return packages.find { it.packageName == packageName } ?: PackageDependency(packageName).also {
            packages.add(it)
        }
    }

    private fun findOrCreateProjectFile(
        files: MutableList<ProjectFile>,
        vFile: VirtualFile,
        project: Project,
        isMaven: Boolean = false
    ): ProjectFile {
        // 计算 filePath
        val basePath = project.basePath?.removeSuffix("/") ?: ""
        val absolutePath = vFile.path
        val relativePath = if (!isMaven) {
            // 本地项目文件
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            // 如果是 Maven 文件，可能要直接存绝对路径
            // 或者你也可以存 jarPath + className
            absolutePath
        }

        return files.find { it.filePath == relativePath } ?: run {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
            val newFile = ProjectFile(
                filePath = relativePath,
                virtualFile = vFile,
                psiFile = psiFile,
                classes = mutableListOf(),
                whole = false
            )
            files.add(newFile)
            newFile
        }
    }

    /**
     * 根据 dependencyInfo 里的 usedMethods，往一个 ProjectFile 里添加对应的 ProjectClass/ProjectMethod
     * 并判断是否为 whole
     */
    private fun fillProjectClassAndMethods(
        projectFile: ProjectFile,
        psiClass: PsiClass,
        dependencyInfo: ClassDependencyInfo
    ) {
        val allMethods = psiClass.methods.filterNot { it.isConstructor }
        val usedMethods = dependencyInfo.usedMethods.filterNot { it.isConstructor }

        val projectMethods = usedMethods.map { usedMethod ->
            ProjectMethod(
                methodName = usedMethod.name,
                parameterTypes = usedMethod.parameterList.parameters.map { p -> p.type.canonicalText },
                psiMethod = usedMethod
            )
        }.toMutableList()

        val isWholeClass = allMethods.isNotEmpty() && allMethods.size == usedMethods.size

        val projectClass = ProjectClass(
            className = psiClass.name ?: "Unnamed",
            psiClass = psiClass,
            methods = projectMethods,
            whole = isWholeClass
        )
        projectFile.classes.add(projectClass)
    }

    private fun generateDependenciesText(
        fileContent: String,
        appFileTree: AppFileTree,
        project: Project
    ): String {
        var fileContent1 = fileContent
        fileContent1 = appFileTree.projectFileTrees.joinToString("\n\n") { projectFileTree ->
            """
=== Project: ${projectFileTree.projectName} ===
${collectFileContents(projectFileTree.files, project).joinToString("\n")}
""".trimIndent()
        }
        return fileContent1
    }

    /**
     * 收集指定文件的内容，供 LLM 使用
     */
    fun collectFileContents(files: List<ProjectFile>, project: Project): List<String> {
        return files.mapNotNull { projectFile ->
            val virtualFile = projectFile.virtualFile

            // 如果用户配置了 whole = true，说明整文件都要
            if (projectFile.whole) {
                // 直接读取文件内容
                FileUtil.readFileInfoForLLM(virtualFile, project)
            } else {
                // 同一个文件内，先把要处理的所有 PsiElement (类、方法等) 都收集到 elementsToProcess
                val elementsToProcess = mutableListOf<PsiElement>()
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@mapNotNull null

                // 遍历用户指定的 Class/Method 信息
                projectFile.classes.forEach { projectClass ->
                    // 查找对应的 PsiClass
                    val psiClass = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).find {
                        if (projectClass.whole) {
                            // 如果指定了 whole = true，则用 qualifiedName 精确匹配
                            it.qualifiedName == projectClass.className
                        } else {
                            // 如果不需要整类，className 可能只写了简单类名，这里做简单匹配
                            it.name == projectClass.className
                        }
                    } ?: return@forEach

                    // 如果用户配置了对整个类都需要
                    if (projectClass.whole) {
                        // 只需要把这个类加进去一次
                        elementsToProcess.add(psiClass)
                    } else {
                        // 如果只需要其中部分方法，则逐个方法查找
                        projectClass.methods.forEach { projectMethod ->
                            val methods = psiClass.findMethodsByName(projectMethod.methodName, false)
                            // 找到参数类型匹配的方法
                            val matchedMethod = methods.find { method ->
                                val paramTypes = method.parameterList.parameters.map { it.type.canonicalText }
                                paramTypes == projectMethod.parameterTypes
                            } ?: return@forEach

                            elementsToProcess.add(matchedMethod)
                        }
                    }
                }

                // 如果本文件收集到了想要处理的 PsiElement
                if (elementsToProcess.isNotEmpty()) {
                    // 此处只调用一次 depsInSingleFile，把当前文件内的所有类和方法一并传入
                    val fileContent = ElementsDepsInSingleFileAction.depsInSingleFile(elementsToProcess, project)
                    if (!fileContent.isNullOrBlank()) {
                        FileUtil.wrapBorder(fileContent.trim())
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }
}