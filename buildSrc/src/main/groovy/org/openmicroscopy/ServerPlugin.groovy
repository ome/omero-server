package org.openmicroscopy

import com.zeroc.gradle.icebuilder.slice.SliceExtension
import com.zeroc.gradle.icebuilder.slice.SlicePlugin
import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.openmicroscopy.utils.SliceHelper

@CompileStatic
class ServerPlugin implements Plugin<Project> {

    public static final String databaseType = "psql"

    @Override
    void apply(Project project) {
        project.pluginManager.apply(SlicePlugin)

        SliceExtension slice = project.extensions.getByType(SliceExtension)
        slice.output = project.file("$project.projectDir/src/${databaseType}/java")

        SliceHelper.newInstance(project, slice).configure()
    }

}
