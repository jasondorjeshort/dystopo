package io.github.jasondorjeshort.dystopo.aircraft

/**
 * T311: test data. [RESPONSE] has the shape of a recorded adsb.lol answer (`/v2/lat/../lon/../dist/..`, readsb JSON:
 * the fields, their types and the `ground` word as the live poll returned them) with synthetic aircraft in it, so no
 * feed data (ODbL) is committed. [table] is a small route table in the shipped format, with real airports.
 */
object AircraftFixtures {
    val RESPONSE = """
        {"ac":[
          {"hex":"a1b2c3","type":"adsb_icao","flight":"ASA1670 ","r":"N000AA","t":"B739","alt_baro":16950,"alt_geom":17025,
           "gs":420.3,"ias":300,"tas":420,"mach":0.66,"track":118.4,"baro_rate":1856,"geom_rate":1792,"squawk":"1234",
           "category":"A3","nav_qnh":1013.6,"lat":46.98261,"lon":-121.19102,"nic":8,"rc":186,"seen_pos":0.7,"version":2,
           "mlat":[],"tisb":[],"messages":10123,"seen":0.2,"rssi":-21.4},
          {"hex":"a1b2c4","type":"adsb_icao","alt_baro":"ground","gs":3.1,"true_heading":270.0,"lat":47.44612,"lon":-122.30441,
           "seen_pos":4.2,"seen":1.1},
          {"hex":"a1b2c5","type":"mode_s","flight":"N0000X  ","alt_baro":9000,"baro_rate":-640,"gs":150,"track":45,"lat":46.7,
           "lon":-121.9,"seen_pos":12.5},
          {"hex":"a1b2c6","type":"mode_s","alt_baro":35000,"seen":3.0}
        ],"msg":"No error","now":1763650000000,"total":4,"ctime":1763650000123,"ptime":2}
    """.trimIndent()

    private val SEA = Airport("KSEA", "SEA", 47.4479, -122.3103, "Seattle–Tacoma International Airport", "Seattle")
    private val DEN = Airport("KDEN", "DEN", 39.8600, -104.6738, "Denver International Airport", "Denver")
    private val PDX = Airport("KPDX", "PDX", 45.5887, -122.5980, "Portland International Airport", "Portland")
    private val CVG = Airport("KCVG", "CVG", 39.0488, -84.6678, "Cincinnati Northern Kentucky International Airport", "Covington")
    private val YKM = Airport("KYKM", "YKM", 46.5682, -120.5440, "Yakima Air Terminal", "Yakima")

    fun table(): String = RouteTable.write(
        listOf(SEA, DEN, PDX, CVG, YKM),
        listOf(intArrayOf(0, 1), intArrayOf(2, 0), intArrayOf(2, 0, 3), intArrayOf(0, 4)),
        linkedMapOf(
            "ASA" to listOf("1670" to 0, "1409" to 1),
            "ABX" to listOf("404" to 2),
            "QXE" to listOf("2172" to 3),
            "UAL" to listOf("719" to 0, "489" to 0), // round 3: the normalisations and the terminal cases
            "SJX" to listOf("32" to 1),
        ),
        listOf("test table", "built 2026-11-20"),
    )

    const val REPLAY = """{"loopS":20,"frames":[
        {"t":0,"ac":[{"hex":"c0ffe1","flight":"ASA1670 ","lat":46.9,"lon":-121.5,"alt_baro":17000,"alt_geom":17075,"gs":420,"track":118,"seen_pos":0.6}]},
        {"t":10,"ac":[{"hex":"c0ffe1","flight":"ASA1670 ","lat":46.89,"lon":-121.47,"alt_baro":17300,"alt_geom":17375,"gs":420,"track":118,"seen_pos":0.6},
                      {"hex":"c0ffe5","lat":46.8,"lon":-121.7,"alt_baro":8200,"gs":95,"track":250,"seen_pos":0.6}]}
    ]}"""
}
