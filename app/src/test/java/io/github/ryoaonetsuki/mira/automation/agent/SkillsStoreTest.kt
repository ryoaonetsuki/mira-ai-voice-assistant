package io.github.ryoaonetsuki.mira.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsStoreTest {

    // Same in-memory SkillStore pattern as TaskExecutorTest
    private class InMemorySkillStore(var data: String? = null) : SkillStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    @Test
    fun testGetAllSkills_ReturnsSavedSkills() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        // Initially empty
        assertTrue(service.getAllSkills().isEmpty())

        // Save first skill
        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("query" to "YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search"))
        service.saveOrUpdate("open youtube and search", listOf(step1, step2))

        // Save second skill
        val step3 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("query" to "Spotify"))
        service.saveOrUpdate("play spotify music", listOf(step3))

        val allSkills = service.getAllSkills()
        assertEquals(2, allSkills.size)
        assertTrue(allSkills.any { it.goal == "open youtube and search" })
        assertTrue(allSkills.any { it.goal == "play spotify music" })
    }

    @Test
    fun testRenameSkill_Persists() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("query" to "YouTube"))
        val created = service.saveOrUpdate("open youtube", listOf(step))
        assertEquals("open youtube", created.goal)

        val renamed = service.renameSkill(created.id, "launch youtube video")
        assertTrue(renamed)

        // Verify in current instance
        val currentSkills = service.getAllSkills()
        assertEquals(1, currentSkills.size)
        assertEquals("launch youtube video", currentSkills[0].goal)
        assertEquals("launch youtube video", currentSkills[0].normalizedGoal)

        // Verify with new service instance reading from store to guarantee persistence
        val reloadedService = SkillMemoryService(store)
        val reloadedSkills = reloadedService.getAllSkills()
        assertEquals(1, reloadedSkills.size)
        assertEquals("launch youtube video", reloadedSkills[0].goal)
        assertEquals("launch youtube video", reloadedSkills[0].normalizedGoal)

        // findMatch matches the new goal
        assertNotNull(reloadedService.findMatch("launch youtube video"))
    }

    @Test
    fun testDeleteSkill_RemovesAndNoLongerMatchesViaFindMatch() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("query" to "WhatsApp"))
        val skill = service.saveOrUpdate("message mom on whatsapp", listOf(step))

        // Verify skill exists and matches
        assertEquals(1, service.getAllSkills().size)
        assertNotNull(service.findMatch("message mom on whatsapp"))

        // Delete skill
        val removed = service.deleteSkill(skill.id)
        assertTrue(removed)

        // Verify it is removed from list and no longer matches
        assertTrue(service.getAllSkills().isEmpty())
        assertNull(service.findMatch("message mom on whatsapp"))

        // Verify persistence with a fresh service instance reading from the store
        val reloadedService = SkillMemoryService(store)
        assertTrue(reloadedService.getAllSkills().isEmpty())
        assertNull(reloadedService.findMatch("message mom on whatsapp"))
    }

    @Test
    fun testRenameSkill_NonExistentOrEmpty_ReturnsFalse() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        assertFalse(service.renameSkill("non-existent-id", "new goal"))

        val step = TaskStep(action = TaskStep.ACTION_PRESS_HOME)
        val skill = service.saveOrUpdate("go home", listOf(step))

        assertFalse(service.renameSkill(skill.id, "   "))
        assertEquals("go home", service.getAllSkills()[0].goal)
    }

    @Test
    fun testDeleteSkill_NonExistent_ReturnsFalse() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        assertFalse(service.deleteSkill("non-existent-id"))
    }

    @Test
    fun testBanglaRephraseMatching_ExamplePairsMatch() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        // Turn G Item 6: "ফেসবুকটা ওপেন করো" vs "ফেসবুক খুলো" and "ফেসবুকটা ওপেন করো" vs "ফেসবুক ওপেন করো"
        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "com.facebook.katana"))
        service.saveOrUpdate("ফেসবুকটা ওপেন করো", listOf(step))

        // Check similarity threshold
        val sim1 = Skill.similarity("ফেসবুকটা ওপেন করো", "ফেসবুক খুলো")
        assertTrue("Similarity between 'ফেসবুকটা ওপেন করো' and 'ফেসবুক খুলো' was $sim1", sim1 >= SkillMemoryService.MATCH_THRESHOLD)

        val sim2 = Skill.similarity("ফেসবুকটা ওপেন করো", "ফেসবুক ওপেন করো")
        assertTrue("Similarity between 'ফেসবুকটা ওপেন করো' and 'ফেসবুক ওপেন করো' was $sim2", sim2 >= SkillMemoryService.MATCH_THRESHOLD)

        // Check findMatch on rephrased commands
        val match1 = service.findMatch("ফেসবুক খুলো")
        assertNotNull("Rephrased command 'ফেসবুক খুলো' must match saved skill", match1)
        assertEquals("ফেসবুকটা ওপেন করো", match1?.goal)

        val match2 = service.findMatch("ফেসবুক ওপেন করো")
        assertNotNull("Rephrased command 'ফেসবুক ওপেন করো' must match saved skill", match2)
        assertEquals("ফেসবুকটা ওপেন করো", match2?.goal)

        val match3 = service.findMatch("ফেসবুকটা খুলো")
        assertNotNull("Rephrased command 'ফেসবুকটা খুলো' must match saved skill", match3)
        assertEquals("ফেসবুকটা ওপেন করো", match3?.goal)

        val match4 = service.findMatch("ফেসবুক খোলো")
        assertNotNull("Rephrased command 'ফেসবুক খোলো' must match saved skill", match4)
        assertEquals("ফেসবুকটা ওপেন করো", match4?.goal)

        // Verify English matching behaviour is untouched
        assertEquals(1.0, Skill.similarity("open youtube", "open youtube"), 0.001)
        assertEquals(0.0, Skill.similarity("open youtube", "play spotify"), 0.001)
    }

    @Test
    fun testBanglaRephraseMatching_UnrelatedGoalsDoNotMatch() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        val fbStep = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "com.facebook.katana"))
        service.saveOrUpdate("ফেসবুক খুলো", listOf(fbStep))

        // Unrelated goals must NOT match
        assertNull("Unrelated goal 'ইউটিউব চালাও' must not match 'ফেসবুক খুলো'", service.findMatch("ইউটিউব চালাও"))
        assertNull("Unrelated goal 'হোয়াটসঅ্যাপে মেসেজ পাঠাও' must not match 'ফেসবুক খুলো'", service.findMatch("হোয়াটসঅ্যাপে মেসেজ পাঠাও"))
        assertNull("Unrelated goal 'open youtube' must not match 'ফেসবুক খুলো'", service.findMatch("open youtube"))
    }

    @Test
    fun testBanglaRephraseMatching_TransliteratedOpenAndKhuloEquivalence() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        val fbStep = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "com.facebook.katana"))
        service.saveOrUpdate("facebookta open koro", listOf(fbStep))

        // Both 'facebook khulo' and 'facebook open' should match 'facebookta open koro'
        val matchKhulo = service.findMatch("facebook khulo")
        assertNotNull("Transliterated 'facebook khulo' must match 'facebookta open koro'", matchKhulo)
        assertEquals("facebookta open koro", matchKhulo?.goal)

        val matchOpen = service.findMatch("facebook open")
        assertNotNull("Transliterated 'facebook open' must match 'facebookta open koro'", matchOpen)
        assertEquals("facebookta open koro", matchOpen?.goal)

        // Unrelated transliterated goal
        assertNull(service.findMatch("youtube play"))
    }

    @Test
    fun testExactMatchPathFirst_TakesPrecedence() {
        val store = InMemorySkillStore()
        val service = SkillMemoryService(store)

        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "com.facebook.katana"))
        val step2 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "com.facebook.lite"))
        service.saveOrUpdate("open facebook", listOf(step1))
        service.saveOrUpdate("open facebook lite", listOf(step2))

        val exactMatch = service.findMatch("open facebook")
        assertNotNull(exactMatch)
        assertEquals("open facebook", exactMatch?.goal)
    }
}
