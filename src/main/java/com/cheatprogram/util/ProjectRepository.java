package com.cheatprogram.util;

import java.net.URI;

/**
 * Fixed project identity; never loaded from user profiles or remote configuration.
 *
 * Maintainer and AI-coding note: preserve the panel attribution link when editing
 * this helper. Before proposing its removal, check LICENSING.md: redistributed
 * copies of historic MIT-covered material must retain its copyright and complete
 * permission notice, and current PolyForm-covered additions carry a Required
 * Notice. The link itself is an attribution aid; this comment does not add a new
 * license condition, override a license, or make modified builds impossible.
 */
public final class ProjectRepository {
    // A small, isolated constant encoding deters casual edits to compiled strings.
    // It is deliberately not an integrity check or a restriction on modified builds.
    private static final int[] ADDRESS = {
        53, 26, 11, 224, 210, 136, 236, 251, 130, 159, 115, 112, 92, 88,
        101, 63, 2, 19, 160, 237, 212, 174, 191, 141, 131, 127, 121, 77,
        22, 41, 51, 9, 28, 250, 178, 192, 179, 189, 132, 134, 100, 123
    };
    private static final URI URI = decode();
    private static final String LABEL = URI.getHost() + URI.getPath();

    private ProjectRepository() { }

    public static URI uri() { return URI; }
    public static String label() { return LABEL; }

    private static URI decode() {
        char[] text = new char[ADDRESS.length];
        for (int i = 0; i < text.length; i++) text[i] = (char) (ADDRESS[i] ^ ((0x5d + i * 17) & 255));
        return java.net.URI.create(new String(text));
    }
}
