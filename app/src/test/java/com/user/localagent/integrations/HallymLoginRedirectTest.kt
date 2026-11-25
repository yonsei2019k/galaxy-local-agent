package com.user.localagent.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HallymLoginRedirectTest {
    @Test fun upgradesSameSiteGetWithoutRewritingTheDestination() {
        assertEquals("https://hallymedu.co.kr/",hallymSecureLoginRedirect("http://hallymedu.co.kr","GET","hallymedu.co.kr"))
        assertEquals("https://hallymedu.co.kr/new_home/My/?course=a%20b",
            hallymSecureLoginRedirect("http://hallymedu.co.kr/new_home/My/?course=a%20b","GET","hallymedu.co.kr"))
    }

    @Test fun rejectsPostAndOtherHosts() {
        val host="hallymedu.co.kr"
        assertNull(hallymSecureLoginRedirect("http://$host/process.asp","POST",host))
        assertNull(hallymSecureLoginRedirect("http://$host@other.example/login","GET",host))
        assertNull(hallymSecureLoginRedirect("http://$host:8080/login","GET",host))
        assertNull(hallymSecureLoginRedirect("http://$host/login","GET","other.example"))
    }
}
