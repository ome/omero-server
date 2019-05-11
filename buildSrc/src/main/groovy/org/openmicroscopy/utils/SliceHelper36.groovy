package org.openmicroscopy.utils

import com.zeroc.gradle.icebuilder.slice.SliceExtension
import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet

@CompileStatic
class SliceHelper36 extends SliceHelper {

    private final Project project

    private SliceExtension ice

    SliceHelper36(Project project, SliceExtension ice) {
        this.project = project
        this.ice = ice
    }

    @Override
    void configure() {
        configureSliceDependencies()
        configureSliceSourceSets()
    }

    void configureSliceDependencies() {
        project.plugins.withType(JavaPlugin) {
            project.dependencies.add(JavaPlugin.API_CONFIGURATION_NAME,
                    "com.zeroc:ice:3.6.4")
        }
    }

    void configureSliceSourceSets() {
        project.plugins.withType(JavaPlugin) {
            JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
            SourceSet main = javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            main.java.srcDirs("src/main/ice36", ice.output)
        }
    }

}
