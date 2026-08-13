package com.reichi.arflioaddon.filmpalast

import com.lagradost.cloudstream3.extractors.FileMoonSx
import com.lagradost.cloudstream3.extractors.Supervideo
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.Voe1

/**
 * Custom hoster extractor aliases used by Filmpalast.
 * Each maps a Filmpalast hoster domain onto a known cloudstream3 extractor base class.
 */
class Ryderjet : VidHidePro() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class AbstreamTo : Supervideo() {
    override var name = "Abstream"
    override var mainUrl = "https://abstream.to"
}
