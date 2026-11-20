package com.thunder.linespeaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSourcePolicyTest {
    @Test fun acceptsOnlyOfficialLineOaPackage() {
        assertTrue(LineSourcePolicy.accepts("com.linecorp.lineoa"))
        assertFalse(LineSourcePolicy.accepts("jp.naver.line.android"))
        assertFalse(LineSourcePolicy.accepts("com.google.android.gm"))
        assertFalse(LineSourcePolicy.accepts("com.facebook.katana"))
        assertFalse(LineSourcePolicy.accepts("com.google.android.apps.messaging"))
        assertFalse(LineSourcePolicy.accepts("com.kasikorn.retail.mbanking.wap"))
        assertFalse(LineSourcePolicy.accepts("com.linecorp.lineoa.fake"))
        assertFalse(LineSourcePolicy.accepts(null))
        assertFalse(LineSourcePolicy.accepts(""))
    }
}
