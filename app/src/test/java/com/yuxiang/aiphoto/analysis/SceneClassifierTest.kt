package com.yuxiang.aiphoto.analysis

import com.google.common.truth.Truth.assertThat
import com.yuxiang.aiphoto.model.NormalizedRect
import com.yuxiang.aiphoto.model.SceneType
import org.junit.Test

class SceneClassifierTest {
    @Test
    fun frontCameraFace_isSelfie() {
        val scene = SceneClassifier.classify(
            faceCount = 1,
            subjectBox = NormalizedRect(0.2f, 0.1f, 0.8f, 0.85f),
            isFrontCamera = true,
        )

        assertThat(scene).isEqualTo(SceneType.SELFIE)
    }

    @Test
    fun shortLowSubject_isPetOrChild() {
        val scene = SceneClassifier.classify(
            faceCount = 0,
            subjectBox = NormalizedRect(0.24f, 0.48f, 0.72f, 0.9f),
            isFrontCamera = false,
        )

        assertThat(scene).isEqualTo(SceneType.PET_OR_CHILD)
    }
}

