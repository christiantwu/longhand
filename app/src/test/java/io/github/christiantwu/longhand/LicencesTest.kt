package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.ui.Licences
import org.junit.Assert.assertEquals
import org.junit.Test

class LicencesTest {

    @Test fun proseParagraphFlows() {
        val text = """
            |   2. Grant of Copyright License. Subject to the terms and conditions of
            |      this License, each Contributor hereby grants to You a perpetual,
            |      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
            |      copyright license.""".trimMargin()
        assertEquals(
            "   2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor " +
                "hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license.",
            Licences.reflow(text),
        )
    }

    @Test fun headersListsAndTablesStayAsWritten() {
        val header = "=".repeat(80) + "\npiper-phonemize 1.2.0 (csukuangfj fork, commit f3ff95afc03640bc1399e113e83361192a2fafb4)\nMIT\nhttps://github.com/csukuangfj/piper-phonemize"
        assertEquals(header, Licences.reflow(header))

        val list = "The library contains these pieces, each under its own licence:\n- the UTF-8 decoder from Bjoern Hoehrmann (MIT)\n- parts of Google Abseil (Apache-2.0)"
        assertEquals(list, Licences.reflow(list))

        val table = "Component              Licence          Version and notes for this one\nkissfft                BSD-3-Clause     commit febd4caeed32e33ad8b2e0bb5ea7"
        assertEquals(table, Licences.reflow(table))

        val shortLines = "Copyright (C) 2012 The Android Open Source Project\nAll rights reserved."
        assertEquals(shortLines, Licences.reflow(shortLines))

        val titled = "Protocol Buffers Java Lite runtime 4.28.2 (repackaged in AndroidX DataStore)\nSource: https://github.com/protocolbuffers/protobuf"
        assertEquals(titled, Licences.reflow(titled))

        val holders = "Copyright (c) 2022-2025 Xiaomi Corporation (Fangjun Kuang) and others\nCopyright (c) 2024 Brno University of Technology (Karel Vesely), Brno"
        assertEquals(holders, Licences.reflow(holders))
    }
}
