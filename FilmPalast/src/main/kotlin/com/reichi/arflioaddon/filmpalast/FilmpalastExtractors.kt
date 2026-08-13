package com.reichi.arflioaddon.filmpalast

import com.lagradost.cloudstream3.extractors.Supervideo
import com.lagradost.cloudstream3.extractors.VidHidePro

/**
 * Custom hoster extractor aliases used by Filmpalast that are NOT in cloudstream3's
 * built-in extractor list. Domains like voe.sx, firestream.to, filemoon.sx are built-in
 * and matched automatically; only Filmpalast-specific aliases live here.
 */
class Ryderjet : VidHidePro() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class AbstreamTo : Supervideo() {
    override var name = "Abstream"
    override var mainUrl = "https://abstream.to"
}
