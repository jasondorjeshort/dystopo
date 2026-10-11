package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.sources.elevation.AustriaEditions as SharedAustriaEditions
import io.github.jasondorjeshort.dystopo.sources.elevation.AustriaSource as SharedAustriaSource
import io.github.jasondorjeshort.dystopo.sources.elevation.BestSource as SharedBestSource
import io.github.jasondorjeshort.dystopo.sources.elevation.BorderMask as SharedBorderMask
import io.github.jasondorjeshort.dystopo.sources.elevation.CanadaSource as SharedCanadaSource
import io.github.jasondorjeshort.dystopo.sources.elevation.CopernicusSource as SharedCopernicusSource
import io.github.jasondorjeshort.dystopo.sources.elevation.Destripe as SharedDestripe
import io.github.jasondorjeshort.dystopo.sources.elevation.Geoids as SharedGeoids
import io.github.jasondorjeshort.dystopo.sources.elevation.LidarSource as SharedLidarSource
import io.github.jasondorjeshort.dystopo.sources.elevation.Lerc2 as SharedLerc2
import io.github.jasondorjeshort.dystopo.sources.elevation.NationalDem as SharedNationalDem
import io.github.jasondorjeshort.dystopo.sources.elevation.NewZealandSource as SharedNewZealandSource
import io.github.jasondorjeshort.dystopo.sources.elevation.NorwaySource as SharedNorwaySource
import io.github.jasondorjeshort.dystopo.sources.elevation.OverviewLevels as SharedOverviewLevels
import io.github.jasondorjeshort.dystopo.sources.elevation.ProjGeoid as SharedProjGeoid
import io.github.jasondorjeshort.dystopo.sources.elevation.RemoteTiff as SharedRemoteTiff
import io.github.jasondorjeshort.dystopo.sources.elevation.SwissAltiSource as SharedSwissAltiSource
import io.github.jasondorjeshort.dystopo.sources.elevation.TerrariumSource as SharedTerrariumSource
import io.github.jasondorjeshort.dystopo.sources.elevation.ThreeDepBestSource as SharedThreeDepBestSource
import io.github.jasondorjeshort.dystopo.sources.elevation.ThreeDepSource as SharedThreeDepSource

// T80: the elevation readers moved to `shared` (package `...dystopo.sources.elevation`) so the phone runs the
// same code. These aliases keep the server-side names, so the devtool, `serve --live`, the standalone server and
// the world tour use them exactly as before. Cache directories still come from `Caches.root` (which now sets
// `SourceIo.cacheRoot`), and `Http.beforeNetwork` is the readers' `SourceIo.beforeNetwork`.

typealias CopernicusSource = SharedCopernicusSource
typealias ThreeDepSource = SharedThreeDepSource
typealias Destripe = SharedDestripe
typealias LidarSource = SharedLidarSource
typealias ThreeDepBestSource = SharedThreeDepBestSource
typealias BestSource = SharedBestSource
typealias SwissAltiSource = SharedSwissAltiSource
typealias NorwaySource = SharedNorwaySource
typealias AustriaSource = SharedAustriaSource
typealias AustriaEditions = SharedAustriaEditions
typealias CanadaSource = SharedCanadaSource
typealias NewZealandSource = SharedNewZealandSource
typealias Lerc2 = SharedLerc2
typealias NationalDem = SharedNationalDem
typealias Geoids = SharedGeoids
typealias ProjGeoid = SharedProjGeoid
typealias RemoteTiff = SharedRemoteTiff
typealias OverviewLevels = SharedOverviewLevels
typealias BorderMask = SharedBorderMask
typealias TerrariumSource = SharedTerrariumSource
