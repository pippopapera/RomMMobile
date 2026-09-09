package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.core.design.HeightClass
import com.rommmobile.app.core.design.LayoutClass
import com.rommmobile.app.core.design.NavMode
import com.rommmobile.app.core.design.ScreenShape
import com.rommmobile.app.core.design.WidthClass
import com.rommmobile.app.core.storage.normalizeFolderName
import com.rommmobile.app.data.repo.Rom
import org.junit.Test

class MatchingTest {

    @Test
    fun `name key ignores archive and inner extension`() {
        assertThat(Rom.nameKeyOf("Super Mario World (USA).sfc")).isEqualTo("super mario world (usa)")
        assertThat(Rom.nameKeyOf("Super Mario World (USA).sfc.zip")).isEqualTo("super mario world (usa)")
        assertThat(Rom.nameKeyOf("Super Mario World (USA).7z")).isEqualTo("super mario world (usa)")
        // Exact match only: no substring false positives between "Super Mario" and "Super Mario World".
        assertThat(Rom.nameKeyOf("Super Mario (USA).sfc")).isNotEqualTo(Rom.nameKeyOf("Super Mario World (USA).sfc"))
    }

    @Test
    fun `folder names match case-insensitively without separators`() {
        assertThat(normalizeFolderName("Mega Drive")).isEqualTo(normalizeFolderName("megadrive"))
        assertThat(normalizeFolderName("neo-geo-pocket-color")).isEqualTo(normalizeFolderName("NeoGeoPocketColor"))
        assertThat(normalizeFolderName("snes")).isNotEqualTo(normalizeFolderName("nes"))
    }

    @Test
    fun `retroid pocket classic is compact but short and uses the rail`() {
        val rp = LayoutClass(widthDp = 472, heightDp = 411, isTv = false, fontScale = 1f)
        assertThat(rp.widthClass).isEqualTo(WidthClass.COMPACT)
        assertThat(rp.heightClass).isEqualTo(HeightClass.SHORT)
        assertThat(rp.shape).isEqualTo(ScreenShape.SQUARE)
        assertThat(rp.navMode).isEqualTo(NavMode.RAIL_COMPACT)
        assertThat(rp.topBarHeight.value).isEqualTo(40f)
    }

    @Test
    fun `phone uses the bottom bar and tablet dock uses two panes`() {
        val phone = LayoutClass(393, 873, isTv = false, fontScale = 1f)
        assertThat(phone.navMode).isEqualTo(NavMode.BOTTOM_BAR)
        assertThat(phone.twoPane).isFalse()
        val dock = LayoutClass(1280, 800, isTv = false, fontScale = 1f)
        assertThat(dock.twoPane).isTrue()
        assertThat(dock.navMode).isEqualTo(NavMode.RAIL)
        val tv = LayoutClass(960, 540, isTv = true, fontScale = 1f)
        assertThat(tv.navMode).isEqualTo(NavMode.RAIL_TV)
        assertThat(tv.overscanInset.value).isGreaterThan(0f)
    }
}
