package org.openmicroscopy.utils

import com.zeroc.gradle.icebuilder.slice.SliceExtension
import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.Project

@CompileStatic
abstract class SliceHelper {

    static SliceHelper newInstance(Project project, SliceExtension slice) {
        String iceVersion = slice.iceVersion as String
        SliceHelper sliceHelper = iceVersion.contains("3.6") ?
                new SliceHelper36(project, slice) : null
        if (sliceHelper == null) {
            throw new GradleException("Unknown ice version")
        }
        return sliceHelper
    }

    abstract void configure()

}
