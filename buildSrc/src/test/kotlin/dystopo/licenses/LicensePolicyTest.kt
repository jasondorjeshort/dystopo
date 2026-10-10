package dystopo.licenses

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Classification against the real gradle/licenses/allowed-licenses.txt. Run: ./gradlew -p buildSrc test */
class LicensePolicyTest {
    private val policy = LicensePolicy(LicensePolicy.parseAllowed(File("../gradle/licenses/allowed-licenses.txt").readLines()))

    private fun category(name: String, url: String = "") = policy.classify(DeclaredLicense(name, url)).category

    private fun assertAll(expected: LicenseCategory, vararg names: String) {
        for (name in names) assertEquals(expected, category(name), name)
    }

    @Test fun permissive() = assertAll(
        LicenseCategory.PERMISSIVE,
        "The Apache Software License, Version 2.0", "Apache License, Version 2.0", "Apache-2.0", "Apache 2",
        "MIT", "The MIT License (MIT)", "BSD-3-Clause", "BSD 2-Clause License", "BSD", "0BSD", "ISC License",
        "Eclipse Public License, Version 2.0", "EPL 2.0", "The Eclipse Public License Version 1.0",
        "Eclipse Distribution License - v 1.0", "Mozilla Public License, Version 2.0", "CC0", "CC-BY-2.5",
        "Unicode-3.0", "Bouncy Castle Licence", "Go License", "HSQLDB License, a BSD open source license",
    )

    @Test fun gplWithClasspathExceptionIsPermissive() = assertAll(
        LicenseCategory.PERMISSIVE,
        "CDDL + GPLv2 with classpath exception", "GPL2 w/ CPE", "GNU General Public License, version 2 with the GNU Classpath Exception",
    )

    @Test fun gplFamily() = assertAll(
        LicenseCategory.GPL,
        "GPL", "GPLv2", "GPL2", "GPL-2.0", "GPL-3.0-only", "GNU General Public License v3.0", "General Public License",
        "GNU Affero General Public License", "AGPL-3.0", "The GNU General Public License, v2 with Universal FOSS Exception, v1.0",
        "GNU GPL",
    )

    @Test fun lgpl() = assertAll(
        LicenseCategory.LGPL,
        "LGPL-2.1", "Lesser General Public License (LGPL)", "GNU Lesser General Public License version 3",
        "GNU Library General Public License", "LGPL-2.1-or-later", "GNU Lesser Public License",
    )

    @Test fun unknown() {
        assertAll(LicenseCategory.UNKNOWN, "EPSG database distribution license", "BSD License for HSQL", "Proprietary")
        assertEquals(LicenseCategory.GPL, category("", "https://www.gnu.org/licenses/gpl-3.0.txt"))
        assertEquals(LicenseCategory.PERMISSIVE, category("", "https://www.apache.org/licenses/LICENSE-2.0.txt"))
    }

    @Test fun artifactTakesBestOption() {
        fun of(vararg names: String) = policy.categoryOf(names.map { policy.classify(DeclaredLicense(it)) })
        assertEquals(LicenseCategory.PERMISSIVE, of("LGPL-2.1-or-later", "Apache-2.0"))
        assertEquals(LicenseCategory.PERMISSIVE, of("GPL2 w/ CPE", "EPL 2.0"))
        assertEquals(LicenseCategory.LGPL, of("GPL-3.0", "LGPL-3.0"))
        assertEquals(LicenseCategory.UNKNOWN, of("GPL-3.0", "Some custom license"))
        assertEquals(LicenseCategory.GPL, of("GPL-3.0"))
        assertEquals(LicenseCategory.UNKNOWN, of())
    }

    /** One string naming a copyleft license and a permissive one must not pass as permissive. */
    @Test fun copyleftWinsOverAllowlistTokenInOneString() {
        assertAll(
            LicenseCategory.GPL,
            "GPL ISC", "GPL-3.0 MIT", "GNU GPL v3 or BSD-3-Clause", "Apache-2.0 / GPL-2.0", "AGPL-3.0, Zlib",
            "GPL-2.0 Public Domain", "GPL-3.0 with CC0 docs",
        )
        assertAll(LicenseCategory.LGPL, "LGPL-2.1, Apache-2.0", "MIT and LGPL-3.0", "GNU Lesser General Public License (EPL 1.0)")
        assertEquals(LicenseCategory.GPL, category("MIT", "https://www.gnu.org/licenses/gpl-3.0.txt"))
        // The classpath exception is the one allowlist entry that may match alongside a GPL pattern.
        assertEquals(LicensePolicy.CLASSPATH_EXCEPTION_ID, policy.classify(DeclaredLicense("GPL2 w/ CPE")).id)
        assertEquals(LicenseCategory.PERMISSIVE, category("CDDL + GPLv2 with classpath exception"))
        assertEquals(LicenseCategory.PERMISSIVE, category("GPL-2.0", "https://www.gnu.org/software/classpath/license.html"))
    }

    @Test fun classpathExceptionIdIsInTheAllowlist() {
        val ids = LicensePolicy.parseAllowed(File("../gradle/licenses/allowed-licenses.txt").readLines()).map { it.first }
        assert(LicensePolicy.CLASSPATH_EXCEPTION_ID in ids) { "allowed-licenses.txt lost ${LicensePolicy.CLASSPATH_EXCEPTION_ID}" }
    }

    @Test fun choiceNoteFlagsMixedCategories() {
        fun note(vararg names: String) = policy.choiceNote(names.map { policy.classify(DeclaredLicense(it)) })
        assertEquals(
            "multiple licenses, best chosen: Apache-2.0 [permissive] over LGPL-2.1-or-later [lgpl]",
            note("LGPL-2.1-or-later", "Apache-2.0"),
        )
        assertEquals(
            "multiple licenses, best chosen: EPL-2.0 [permissive] over GPL-2.0 [gpl], Weird [unknown]",
            note("GPL-2.0", "EPL 2.0", "Weird"),
        )
        // Same category throughout: no choice worth flagging.
        assertEquals(null, note("Apache-2.0", "MIT"))
        assertEquals(null, note("Apache-2.0"))
        assertEquals(null, note())
    }

    @Test fun parsesConfig() {
        val overrides = LicensePolicy.parseOverrides(listOf("﻿# comment", "", "a.b:c = LGPL-2.1  # why"))
        assertEquals(mapOf("a.b:c" to "LGPL-2.1"), overrides)
    }
}
