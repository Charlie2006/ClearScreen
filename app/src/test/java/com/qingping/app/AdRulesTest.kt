package com.qingping.app

import org.junit.Assert.*
import org.junit.Test

class AdRulesTest {
    private val screen = AdRules.Screen(AdRules.Box(0, 0, 1080, 2400), 3000, false, false, false)
    private fun node(text: String = "跳过", id: String = "", box: AdRules.Box = AdRules.Box(860, 90, 1020, 160),
                     visible: Boolean = true, enabled: Boolean = true, editable: Boolean = false,
                     description: String = "") = AdRules.Node(text, description, id, box, visible, enabled, editable)
    @Test fun plainTopRightSkipNeedsNoAdWordAtStartup() {
        val result = AdRules.decide(node(), screen)
        assertTrue(result.click)
        assertTrue(result.reason.contains("开屏"))
    }
    @Test fun commonChineseAndEnglishCountdownVariants() {
        for (text in listOf("跳过 5s", "5秒｜跳过", "跳过（５秒）", "跳过 | 3", "3s 跳过", "Skip (5s)", "5 Skip")) {
            assertTrue(text, AdRules.decide(node(text), screen.copy(ageMs = 9000)).click)
        }
    }
    @Test fun ordinarySkipOutsideStartupIsRejectedWithReason() {
        val d = AdRules.decide(node(), screen.copy(ageMs = 16000))
        assertFalse(d.click); assertTrue(d.candidate); assertTrue(d.reason.contains("超过开屏"))
    }
    @Test fun lowerPageSkipWithoutAdEvidenceIsNotAnAd() {
        val d = AdRules.decide(node(box = AdRules.Box(860, 1800, 1020, 1870)), screen)
        assertFalse(d.click); assertTrue(d.reason.contains("不在右上角"))
    }
    @Test fun motionAdWithCornerSkipWithinWindow() {
        assertTrue(AdRules.decide(node(), screen.copy(ageMs = 9000, motion = true)).click)
    }
    @Test fun motionTextAloneNeverBecomesClickTarget() {
        assertFalse(AdRules.decide(node("摇一摇，了解详情"), screen.copy(motion = true)).candidate)
    }
    @Test fun adjacentTimerSupportsDelayedSplash() {
        assertTrue(AdRules.decide(node(), screen.copy(ageMs = 9000, nearbyTimer = true)).click)
    }
    @Test fun descriptionOnlySkipIsSupported() {
        assertTrue(AdRules.decide(node(text = "", description = "跳过 3秒"), screen).click)
    }
    @Test fun explicitAdDismissalIsSupportedAwayFromCorner() {
        assertTrue(AdRules.decide(node("关闭广告", box = AdRules.Box(500, 1400, 600, 1480)), screen.copy(ageMs = 9000)).click)
    }
    @Test fun sdkIdsAllowTextlessButtons() {
        for (id in listOf("com.demo:id/tt_splash_skip_btn", "com.demo:id/ksad_splash_skip_view", "com.demo:id/ad_close", "com.demo:id/splashSkipView"))
            assertTrue(id, AdRules.decide(node("", id), screen).click)
    }
    @Test fun substringAdInDownloadDoesNotEstablishAdContext() {
        assertFalse(AdRules.decide(node("", "com.demo:id/download_close"), screen).click)
    }
    @Test fun parentContainerLabelIsNeverWholeScreenClick() {
        assertFalse(AdRules.decide(node("跳过广告", box = screen.box), screen).click)
    }
    @Test fun disabledAndInvisibleAndEditableNodesAreNotClicked() {
        assertFalse(AdRules.decide(node(enabled = false), screen).click)
        assertFalse(AdRules.decide(node(visible = false), screen).click)
        assertFalse(AdRules.decide(node(editable = true), screen).click)
    }
    @Test fun skipLoginAndInstructionSentencesAreRejected() {
        for (text in listOf("跳过登录", "点击右上角跳过", "跳过这一题", "跳过视频")) {
            val d = AdRules.decide(node(text), screen)
            assertTrue(d.candidate); assertFalse(text, d.click)
        }
    }
    @Test fun privacyAndOnboardingPagePreventsCornerFalsePositive() {
        val d = AdRules.decide(node(), screen.copy(onboarding = true))
        assertFalse(d.click); assertTrue(d.reason.contains("隐私"))
    }
    @Test fun splashIdAloneDoesNotOverrideWelcomeGuide() {
        assertFalse(AdRules.decide(node(id = "com.demo:id/splash_skip"), screen.copy(onboarding = true)).click)
    }
    @Test fun ordinaryCloseWithoutAdContextIsRejected() {
        assertFalse(AdRules.decide(node("关闭"), screen).click)
        assertTrue(AdRules.decide(node("关闭"), screen.copy(ad = true)).click)
    }
    @Test fun outOfWindowAndZeroBoundsAreRejected() {
        assertFalse(AdRules.decide(node(box = AdRules.Box(1000, 90, 1200, 160)), screen).click)
        assertFalse(AdRules.decide(node(box = AdRules.Box(0, 0, 0, 0)), screen).click)
    }
    @Test fun zeroWidthCharsAndFullWidthDigitsNormalize() {
        assertEquals("跳过(5秒)", AdRules.normalize("跳\u200b过（５ 秒）"))
        assertTrue(AdRules.decide(node("跳\u200b过"), screen).click)
    }
    @Test fun windowOriginIsRespectedForSplitScreen() {
        val shifted = screen.copy(box = AdRules.Box(100, 500, 1180, 2900))
        assertTrue(AdRules.decide(node(box = AdRules.Box(960, 590, 1120, 660)), shifted).click)
    }
    @Test fun textlessSkipIdUsesCornerAndStartupConstraints() {
        assertTrue(AdRules.decide(node("", "com.demo:id/tv_skip"), screen).click)
        assertFalse(AdRules.decide(node("", "com.demo:id/tv_skip"), screen.copy(ageMs = 20000)).click)
        assertFalse(AdRules.decide(node("", "com.demo:id/tv_skip", AdRules.Box(860, 1800, 1020, 1870)), screen).click)
    }
    @Test fun misleadingSkipIdWithUnrelatedTextIsRejected() {
        assertFalse(AdRules.decide(node("登录", "com.demo:id/tv_skip"), screen).click)
    }
    @Test fun negatedOrExplanatoryAdTextDoesNotItselfBecomeAButton() {
        assertFalse(AdRules.decide(node("本软件可以关闭广告"), screen).click)
        assertFalse(AdRules.decide(node("广告"), screen.copy(ad = true)).candidate)
    }
    @Test fun directStartupSkipWorksAtBottomWithoutAdEvidence() {
        val bottom = node(box = AdRules.Box(100, 1800, 300, 1870))
        assertFalse(AdRules.decide(bottom, screen).click)
        val d = AdRules.decide(bottom, screen.copy(startupSkip = true))
        assertTrue(d.click); assertTrue(d.reason.contains("开屏直点"))
    }
    @Test fun directModeDeliberatelyAllowsSkipOnboarding() {
        assertTrue(AdRules.decide(node("跳过登录"), screen.copy(startupSkip = true, onboarding = true)).click)
        assertFalse(AdRules.decide(node("跳过登录"), screen.copy(startupSkip = false, onboarding = true)).click)
    }
    @Test fun allModesStopAtTenSeconds() {
        val n = node(box = AdRules.Box(100, 1800, 300, 1870))
        assertTrue(AdRules.decide(n, screen.copy(startupSkip = true, ageMs = 9999)).click)
        assertFalse(AdRules.decide(n, screen.copy(startupSkip = true, ageMs = 10000)).click)
        assertFalse(AdRules.decide(node("关闭广告"), screen.copy(startupSkip = true, ageMs = 10000)).click)
    }
    @Test fun directModeDoesNotBypassNodeSafetyChecks() {
        val direct = screen.copy(startupSkip = true)
        for (n in listOf(node(visible = false), node(enabled = false), node(editable = true),
            node(box = screen.box), node(box = AdRules.Box(-5, 90, 100, 160))))
            assertFalse(AdRules.decide(n, direct).click)
    }
    @Test fun directModeDoesNotTreatCloseOrBareIdAsSkipText() {
        val direct = screen.copy(startupSkip = true)
        assertFalse(AdRules.decide(node("关闭", box = AdRules.Box(100, 1800, 300, 1870)), direct).click)
        assertFalse(AdRules.decide(node("", "com.demo:id/skip", AdRules.Box(100, 1800, 300, 1870)), direct).click)
    }
    @Test fun directModeHandlesDescriptionAndCountdownAwayFromCorner() {
        val direct = screen.copy(startupSkip = true)
        assertTrue(AdRules.decide(node("", box = AdRules.Box(100, 1800, 300, 1870), description = "跳过 5s"), direct).click)
        assertTrue(AdRules.decide(node("Skip (5s)", box = AdRules.Box(100, 1800, 300, 1870)), direct).click)
    }

    @Test fun evenStrongAdEvidenceCannotClickAfterDeadline() {
        for (age in listOf(-1L, 10000L, 30000L)) {
            val expired = screen.copy(ageMs = age, ad = true, motion = true, startupSkip = true)
            for (n in listOf(node(), node("关闭广告"), node("", "com.demo:id/ad_close")))
                assertFalse("age=$age node=$n", AdRules.decide(n, expired).click)
        }
    }
}
