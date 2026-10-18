/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.spike

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider

/**
 * Disposable compatibility spike for InnerTubeX v0.2.1.
 *
 * This type intentionally references public InnerTubeX symbols so the Kotlin compiler is forced to
 * read the dependency's metadata during `assembleCoreDebug`. No runtime/network behavior is
 * performed. Remove this class (and the dependency) after the compatibility decision.
 */
internal object InnerTubeXCompatibilitySpike {

    fun typeCheck(
        extractor: InnerTubeExtractor?,
        hints: ContentHints,
        stream: ExtractedStream?,
        tokenProvider: TokenProvider?,
        error: StreamResolveException?,
    ): String? = stream?.clientName
}
