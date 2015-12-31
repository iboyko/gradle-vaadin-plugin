/*
* Copyright 2015 John Ahlroos
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package fi.jasoft.plugin

import fi.jasoft.plugin.configuration.VaadinPluginExtension
import fi.jasoft.plugin.tasks.*
import org.gradle.api.Action
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.plugins.WarPluginConvention
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.util.VersionNumber

class GradleVaadinPlugin implements Plugin<Project> {

    public static final PLUGIN_VERSION
    public static final PLUGIN_PROPERTIES
    public static final PLUGIN_DEBUG_DIR

    public static int PLUGINS_IN_PROJECT = 0;

    public static final String CONFIGURATION_SERVER = 'vaadin-server'
    public static final String CONFIGURATION_CLIENT = 'vaadin-client'
    public static final String CONFIGURATION_TESTBENCH = 'vaadin-testbench'
    public static final String CONFIGURATION_PUSH = 'vaadin-push'
    public static final String CONFIGURATION_JAVADOC = 'vaadin-javadoc'
    public static final String DEFAULT_WIDGETSET = 'com.vaadin.DefaultWidgetSet'
    public static final String CONFIGURATION_PAYARA = 'vaadin-payara'
    public static final String CONFIGURATION_SUPERDEVMODE = 'vaadin-superdevmode'
    public static final String VAADIN_TASK_GROUP = 'Vaadin'
    public static final String VAADIN_UTIL_TASK_GROUP = 'Vaadin Utility'
    public static final String VAADIN_TESTBENCH_TASK_GROUP = 'Vaadin Testbench'
    public static final String VAADIN_DIRECTORY_TASK_GROUP = 'Vaadin Directory'

    static {
        PLUGIN_PROPERTIES = new Properties()
        PLUGIN_PROPERTIES.load(GradleVaadinPlugin.class.getResourceAsStream('/vaadin_plugin.properties'))
        PLUGIN_VERSION = PLUGIN_PROPERTIES.getProperty('version')
        PLUGIN_DEBUG_DIR = PLUGIN_PROPERTIES.getProperty("debugdir")
    }

    static String getVersion() {
        return PLUGIN_VERSION
    }

    static String getDebugDir() {
        return PLUGIN_DEBUG_DIR
    }

    static int getNumberOfPluginsInProject() {
        return PLUGINS_IN_PROJECT
    }

    static boolean isFirstPlugin() {
        return PLUGINS_IN_PROJECT == 1;
    }

    void apply(Project project) {

        def gradle = project.gradle
        def version = VersionNumber.parse(gradle.gradleVersion)
        def requiredVersion = new VersionNumber(2, 6, 0, null)
        if(version.baseVersion < requiredVersion) {
            throw new UnsupportedVersionException("Your gradle version ($version) is too old. Plugin requires Gradle $requiredVersion+")
        }

        PLUGINS_IN_PROJECT++;

        if (firstPlugin) {
            project.logger.quiet("Using Gradle Vaadin Plugin " + PLUGIN_VERSION)
        }

        // Extensions
        project.extensions.create('vaadin', VaadinPluginExtension)

        // Dependency resolution
        gradle.taskGraph.addTaskExecutionListener(new TaskListener())

        // Plugins
        project.plugins.apply(WarPlugin)

        // Repositories
        applyRepositories(project)

        // Dependencies
        applyDependencies(project)

        // Tasks
        applyVaadinTasks(project)
        applyVaadinUtilityTasks(project)
        applyVaadinTestbenchTasks(project)
        applyVaadinDirectoryTasks(project)

        // Add debug information to all compilation results
        def tasks = project.tasks
        tasks.compileJava.options.debugOptions.debugLevel = 'source,lines,vars'

        // Add sources to test classpath
        project.sourceSets.test.runtimeClasspath =
                project.sourceSets.test.runtimeClasspath.plus(project.files(project.sourceSets.main.java.srcDirs))

        // War project should build the widgetset and themes
        def war = project.war
        war.dependsOn(CompileWidgetsetTask.NAME)
        war.dependsOn(CompileThemeTask.NAME)

        // Ensure widgetset is up-2-date
        def resources = project.processResources
        resources.dependsOn(UpdateWidgetsetTask.NAME)

        // Cleanup plugin outputs
        def clean = project.clean
        clean.dependsOn(tasks['clean' + CompileWidgetsetTask.NAME.capitalize()])
        clean.dependsOn(tasks['clean' + RunTask.NAME.capitalize()])
        clean.dependsOn(tasks['clean' + CompileThemeTask.NAME.capitalize()])
        clean.dependsOn(tasks['clean' + SuperDevModeTask.NAME.capitalize()])
        clean.dependsOn(tasks['clean' + DevModeTask.NAME.capitalize()])

        // Utilities
        def artifacts = project.artifacts
        artifacts.add('archives', tasks[BuildSourcesJarTask.NAME])
        artifacts.add('archives', tasks[BuildJavadocJarTask.NAME])

        project.beforeEvaluate { Project p ->
            def plugins = p.plugins
            if (plugins.findPlugin('eclipse') && !plugins.findPlugin('eclipse-wtp')) {
                p.logger.warn("You are using the eclipse plugin which does not support all " +
                        "features of the Vaadin plugin. Please use the eclipse-wtp plugin instead.")
            }
        }

        project.afterEvaluate { Project p ->
            def v = Util.getVaadinVersion(p)
            if(v !=null && v.startsWith("6")){
                p.logger.error("Plugin no longer supports Vaadin 6, to use Vaadin 6 apply an older version of the plugin.")
                throw new InvalidUserDataException("Unsupported Vaadin version.")
            }

            // Remove configurations if the plugin shouldn't manage them
            if(!project.vaadin.manageDependencies){
                project.configurations.removeAll({ Configuration conf ->
                   conf.name.startsWith('vaadin-')
                })
            }


        }
    }

    static void applyRepositories(Project project) {
        project.afterEvaluate {
            if(!project.vaadin.manageRepositories) {
                return
            }

            def repositories = project.repositories

            repositories.mavenCentral()
            repositories.mavenLocal()

            repositories.maven { repository ->
                repository.name = 'Vaadin addons'
                repository.url = 'http://maven.vaadin.com/vaadin-addons'
            }

            repositories.maven { repository ->
                repository.name = 'Vaadin snapshots'
                repository.url = 'http://oss.sonatype.org/content/repositories/vaadin-snapshots'
            }

            repositories.maven { repository ->
                repository.name = 'Jasoft.fi Maven repository'
                repository.url = 'http://mvn.jasoft.fi/maven2'
            }

            repositories.maven { repository ->
                repository.name = 'Bintray.com Maven repository'
                repository.url = 'http://dl.bintray.com/johndevs/maven'
            }

            // Add plugin development repository if specified
            if((debugDir as File)?.exists()
                    && !repositories.findByName('Gradle Vaadin plugin development repository')) {
                if (GradleVaadinPlugin.firstPlugin) {
                    project.logger.lifecycle("Using development libs found at " + debugDir)
                }
                repositories.flatDir(name: 'Gradle Vaadin plugin development repository', dirs: debugDir)
            }
        }
    }

    static void applyDependencies(Project project) {
        def configurations = project.configurations
        def projectDependencies = project.dependencies
        def sources = project.sourceSets.main
        def testSources = project.sourceSets.test

        configurations.create(CONFIGURATION_SERVER, { conf ->
            conf.description = 'Libraries needed by Vaadin server side applications.'
            conf.defaultDependencies { dependencies ->
                def vaadinServer = projectDependencies.create("com.vaadin:vaadin-server:${Util.getVaadinVersion(project)}")
                dependencies.add(vaadinServer)

                def vaadinThemes = projectDependencies.create("com.vaadin:vaadin-themes:${Util.getVaadinVersion(project)}")
                dependencies.add(vaadinThemes)

                def servletAPI = projectDependencies.create('javax.servlet:javax.servlet-api:3.0.1')
                dependencies.add(servletAPI)

                // Theme compiler
                if(!Util.isSassCompilerSupported(project)){
                    File webAppDir = project.convention.getPlugin(WarPluginConvention).webAppDir
                    FileTree themes = project.fileTree(dir: webAppDir.canonicalPath + '/VAADIN/themes', include: '**/styles.scss')
                    if (!themes.isEmpty()) {
                        def themeCompiler = projectDependencies.create("com.vaadin:vaadin-theme-compiler:${Util.getVaadinVersion(project)}")
                        dependencies.add(themeCompiler)
                    }
                }
            }

            sources.compileClasspath += conf
            testSources.compileClasspath += conf
        })

        configurations.create(CONFIGURATION_CLIENT, { conf ->
            conf.description = 'Libraries needed for compiling the widgetset.'
            conf.defaultDependencies { dependencies ->
                if(!project.vaadin.widgetset){
                    def widgetsetCompiled = projectDependencies.create("com.vaadin:vaadin-client-compiled:${Util.getVaadinVersion(project)}")
                    dependencies.add(widgetsetCompiled)
                } else {
                    def vaadinClient = projectDependencies.create("com.vaadin:vaadin-client:${Util.getVaadinVersion(project)}")
                    dependencies.add(vaadinClient)

                    def widgetsetCompiler = projectDependencies.create("com.vaadin:vaadin-client-compiler:${Util.getVaadinVersion(project)}")
                    dependencies.add(widgetsetCompiler)

                    def validationAPI = projectDependencies.create('javax.validation:validation-api:1.0.0.GA')
                    dependencies.add(validationAPI)
                }
            }

            sources.compileClasspath += conf

            testSources.compileClasspath += conf
            testSources.runtimeClasspath += conf
        })

        configurations.create(CONFIGURATION_JAVADOC, { conf ->
            conf.description = 'Libraries for compiling JavaDoc for a Vaadin project.'
            conf.defaultDependencies { dependencies ->
                def portletAPI = projectDependencies.create('javax.portlet:portlet-api:2.0')
                dependencies.add(portletAPI)

                def servletAPI = projectDependencies.create('javax.servlet:javax.servlet-api:3.0.1')
                dependencies.add(servletAPI)

                if(Util.isPushSupported(project)){
                    def push = projectDependencies.create("com.vaadin:vaadin-push:${Util.getVaadinVersion(project)}")
                    dependencies.add(push)
                }
            }
        })

        configurations.create(CONFIGURATION_PAYARA, { conf ->
            conf.description = 'Libraries for running the embedded Payara server'
            conf.defaultDependencies { dependencies ->
                def payaraWebProfile = projectDependencies.create('fish.payara.extras:payara-embedded-web:4.1.152.1')
                dependencies.add(payaraWebProfile)

                def plugin = projectDependencies.create("fi.jasoft.plugin:gradle-vaadin-plugin:${GradleVaadinPlugin.version}")
                dependencies.add(plugin)
            }
        })

        configurations.create(CONFIGURATION_PUSH, { conf ->
            conf.description = 'Libraries needed for using Vaadin Push features.'
            conf.defaultDependencies { dependencies ->
                if(Util.isPushSupportedAndEnabled(project)) {
                    def push = projectDependencies.create("com.vaadin:vaadin-push:${Util.getVaadinVersion(project)}")
                    dependencies.add(push)
                }
            }

            sources.compileClasspath += conf

            testSources.compileClasspath += conf
            testSources.runtimeClasspath += conf
        })

        configurations.create(CONFIGURATION_TESTBENCH, { conf ->
            conf.description = 'Libraries needed by Vaadin Testbench.'
            conf.defaultDependencies { dependencies ->
                if(project.vaadin.testbench.enabled) {
                    def testbench = projectDependencies.create("com.vaadin:vaadin-testbench:${project.vaadin.testbench.version}")
                    dependencies.add(testbench)
                }
            }

            testSources.compileClasspath += conf
            testSources.runtimeClasspath += conf
        })

        configurations.create(CONFIGURATION_SUPERDEVMODE, { conf ->
            conf.description = 'Libraries needed by Vaadin Superdevmode.'
            conf.defaultDependencies { dependencies ->

                def jettyAll = projectDependencies.create( 'org.eclipse.jetty.aggregate:jetty-all-server:8.1.15.v20140411')
                dependencies.add(jettyAll)

                def plugin = projectDependencies.create("fi.jasoft.plugin:gradle-vaadin-plugin:${GradleVaadinPlugin.version}")
                dependencies.add(plugin)

                def asm = projectDependencies.create('org.ow2.asm:asm:5.0.3')
                dependencies.add(asm)

                def asmCommons = projectDependencies.create('org.ow2.asm:asm-commons:5.0.3')
                dependencies.add(asmCommons)

                def jsp = projectDependencies.create('javax.servlet.jsp:jsp-api:2.2')
                dependencies.add(jsp)
            }
        })

        // Ensure vaadin version is correct across configurations
        project.configurations.all { config ->
            configureResolutionStrategy(project, config)
        }
    }

    /**
     * Configures the resolution strategy for a configuration. Ensures Vaadin version is the correct one.
     *
     * @param project
     *      The project of the configuration
     * @param configuration
     *      The configuration
     */
    static void configureResolutionStrategy(Project project, Configuration config) {
        config.resolutionStrategy.eachDependency(new Action<DependencyResolveDetails>() {

            @Override
            void execute(DependencyResolveDetails details) {
                def whitelist = [
                        'com.vaadin:vaadin-client',
                        'com.vaadin:vaadin-client-compiled',
                        'com.vaadin:vaadin-client-compiler',
                        'com.vaadin:vaadin-server',
                        'com.vaadin:vaadin-shared',
                        'com.vaadin:vaadin-themes',
                        'com.vaadin:vaadin-push'
                ]

                def dependency = details.requested
                String group = dependency.group
                String name = dependency.name

                if("$group:$name".toString() in whitelist){
                    details.useVersion Util.getVaadinVersion(project)
                }

                if(config.name == 'vaadin-client') {
                    if(group == 'javax.validation' && name == 'validation-api'){
                        // GWT only supports this version, do not upgrade it
                        details.useVersion '1.0.0.GA'
                    }
                }
            }
        })
    }


    static void applyVaadinTasks(Project project){
        def tasks = project.tasks
        tasks.create(name: CreateProjectTask.NAME, type: CreateProjectTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CreateComponentTask.NAME, type: CreateComponentTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CreateCompositeTask.NAME, type: CreateCompositeTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CreateThemeTask.NAME, type: CreateThemeTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CreateWidgetsetGeneratorTask.NAME, type: CreateWidgetsetGeneratorTask, group: VAADIN_TASK_GROUP)

        tasks.create(name: CompileWidgetsetTask.NAME, type: CompileWidgetsetTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: DevModeTask.NAME, type: DevModeTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: SuperDevModeTask.NAME, type: SuperDevModeTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CompileThemeTask.NAME, type: CompileThemeTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: RunTask.NAME, type: RunTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: UpdateWidgetsetTask.NAME, type: UpdateWidgetsetTask, group: VAADIN_TASK_GROUP)

        tasks.create(name: UpdateAddonStylesTask.NAME, type: UpdateAddonStylesTask, group: VAADIN_TASK_GROUP)
        tasks.create(name: CreateAddonThemeTask.NAME, type: CreateAddonThemeTask, group: VAADIN_TASK_GROUP)
    }

    static void applyVaadinUtilityTasks(Project project) {
        def tasks = project.tasks
        tasks.create(name: BuildSourcesJarTask.NAME, type: BuildSourcesJarTask, group: VAADIN_UTIL_TASK_GROUP)
        tasks.create(name: BuildJavadocJarTask.NAME, type: BuildJavadocJarTask, group: VAADIN_UTIL_TASK_GROUP)
        tasks.create(name: BuildClassPathJar.NAME, type: BuildClassPathJar, group: VAADIN_UTIL_TASK_GROUP)
    }

    static void applyVaadinTestbenchTasks(Project project) {
        def tasks = project.tasks
        tasks.create(name: CreateTestbenchTestTask.NAME, type: CreateTestbenchTestTask, group: VAADIN_TESTBENCH_TASK_GROUP)
    }

    static void applyVaadinDirectoryTasks(Project project) {
        def tasks = project.tasks
        tasks.create(name: DirectorySearchTask.NAME, type: DirectorySearchTask, group: VAADIN_DIRECTORY_TASK_GROUP)
        tasks.create(name: CreateDirectoryZipTask.NAME, type: CreateDirectoryZipTask, group: VAADIN_DIRECTORY_TASK_GROUP)
    }
}
