package com.example

import com.example.engine.CompressionPlanCalculator
import com.example.model.CompressionPreset
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPlanCalculatorTest {

  @Test
  fun `landscape 4K scaled to 1080p preserves 16_9 aspect ratio`() {
    val plan =
      CompressionPlanCalculator.buildPlan(
        sourceWidth = 3840,
        sourceHeight = 2160,
        sourceRotation = 0,
        sourceBitrateBps = 28_000_000,
        preset = CompressionPreset.BALANCED,
        targetResolution = TargetResolution.FHD_1080P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 3500,
      )
    assertTrue(plan.shouldScale)
    assertEquals(1920, plan.targetDisplayWidth)
    assertEquals(1080, plan.targetDisplayHeight)
    assertTrue(plan.targetVideoBitrateBps < 28_000_000)
  }

  @Test
  fun `portrait rotated 90 deg 1920x1080 scales short side to 720p cleanly`() {
    val plan =
      CompressionPlanCalculator.buildPlan(
        sourceWidth = 1920,
        sourceHeight = 1080,
        sourceRotation = 90,
        sourceBitrateBps = 12_000_000,
        preset = CompressionPreset.SMALL_SIZE,
        targetResolution = TargetResolution.HD_720P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 2000,
      )
    assertTrue(plan.shouldScale)
    assertEquals(720, plan.targetDisplayWidth)
    assertEquals(1280, plan.targetDisplayHeight)
    assertTrue(plan.targetVideoBitrateBps < 12_000_000)
  }

  @Test
  fun `never upscales 480p video when 1080p target selected`() {
    val plan =
      CompressionPlanCalculator.buildPlan(
        sourceWidth = 854,
        sourceHeight = 480,
        sourceRotation = 0,
        sourceBitrateBps = 2_000_000,
        preset = CompressionPreset.BALANCED,
        targetResolution = TargetResolution.FHD_1080P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 3500,
      )
    assertFalse(plan.shouldScale)
    assertEquals(854, plan.targetDisplayWidth)
    assertEquals(480, plan.targetDisplayHeight)
    assertTrue(plan.targetVideoBitrateBps < 2_000_000)
  }

  @Test
  fun `compressed filename appends _compressed suffix without duplication`() {
    assertEquals(
      "vacation_clip_compressed.mp4",
      CompressionPlanCalculator.buildCompressedFileName("vacation_clip.mov"),
    )
    assertEquals(
      "vacation_clip_compressed.mp4",
      CompressionPlanCalculator.buildCompressedFileName("vacation_clip_compressed.mp4"),
    )
  }
}
