package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T283: the driving-side table ([DrivingSides]) at public city points, at a pair of public points across each hard
 * border its KDoc lists, and at each `areas.json` area's centre (`everest`, which straddles the Nepal-China border, by
 * a point on each side); and the side reaching the drive search (at the route's start) and the overlay's cells (at the
 * cell's centre).
 */
class DrivingSideTest {
    private val L = DrivingSide.LEFT
    private val R = DrivingSide.RIGHT

    private fun check(points: List<Triple<String, Pair<Double, Double>, DrivingSide>>) {
        val wrong = points.filter { (_, p, side) -> DrivingSides.at(p.first, p.second) != side }
            .map { (name, p, side) -> "$name (${p.first}, ${p.second}): expected $side, got ${DrivingSides.at(p.first, p.second)} from ${DrivingSides.boxAt(p.first, p.second)?.name}" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun p(name: String, lat: Double, lon: Double, side: DrivingSide) = Triple(name, lat to lon, side)

    @Test
    fun citiesReadTheirSide() {
        val cities = listOf(
            p("London", 51.507, -0.128, L), p("Edinburgh", 55.953, -3.188, L), p("Belfast", 54.597, -5.930, L),
            p("Dublin", 53.350, -6.260, L), p("Valletta", 35.899, 14.514, L), p("Nicosia", 35.186, 33.382, L),
            p("Cape Town", -33.925, 18.424, L), p("Johannesburg", -26.204, 28.047, L), p("Windhoek", -22.560, 17.083, L),
            p("Gaborone", -24.654, 25.908, L), p("Harare", -17.829, 31.053, L), p("Lusaka", -15.387, 28.322, L),
            p("Lilongwe", -13.963, 33.774, L), p("Maputo", -25.966, 32.573, L), p("Dar es Salaam", -6.792, 39.208, L),
            p("Nairobi", -1.292, 36.822, L), p("Kampala", 0.348, 32.582, L), p("Port Louis", -20.161, 57.501, L),
            p("Victoria, Seychelles", -4.620, 55.455, L), p("Mumbai", 19.076, 72.878, L), p("Delhi", 28.704, 77.102, L),
            p("Kolkata", 22.573, 88.364, L), p("Chennai", 13.083, 80.271, L), p("Colombo", 6.927, 79.861, L),
            p("Dhaka", 23.811, 90.413, L), p("Karachi", 24.861, 67.010, L), p("Lahore", 31.520, 74.359, L),
            p("Islamabad", 33.684, 73.048, L), p("Kathmandu", 27.717, 85.324, L), p("Thimphu", 27.472, 89.639, L),
            p("Male", 4.175, 73.509, L), p("Bangkok", 13.756, 100.502, L), p("Chiang Mai", 18.788, 98.985, L),
            p("Kuala Lumpur", 3.139, 101.687, L), p("Singapore", 1.352, 103.820, L), p("Jakarta", -6.208, 106.846, L),
            p("Bandar Seri Begawan", 4.903, 114.940, L), p("Dili", -8.556, 125.560, L), p("Port Moresby", -9.443, 147.180, L),
            p("Tokyo", 35.682, 139.692, L), p("Osaka", 34.694, 135.502, L), p("Sapporo", 43.062, 141.354, L),
            p("Naha", 26.212, 127.681, L), p("Sydney", -33.869, 151.209, L), p("Perth", -31.950, 115.860, L),
            p("Hobart", -42.882, 147.327, L), p("Auckland", -36.849, 174.763, L), p("Wellington", -41.287, 174.776, L),
            p("Suva", -18.124, 178.450, L), p("Nuku'alofa", -21.139, -175.204, L), p("Apia", -13.834, -171.760, L),
            p("Honiara", -9.445, 159.973, L), p("Kingston, Jamaica", 17.971, -76.793, L), p("Nassau", 25.048, -77.355, L),
            p("Bridgetown", 13.097, -59.618, L), p("Hamilton, Bermuda", 32.294, -64.783, L),
            p("Paris", 48.857, 2.352, R), p("Madrid", 40.417, -3.704, R), p("Gibraltar", 36.140, -5.353, R),
            p("Lisbon", 38.722, -9.139, R), p("Istanbul", 41.008, 28.978, R), p("Moscow", 55.756, 37.617, R),
            p("Seattle", 47.606, -122.332, R), p("New York", 40.713, -74.006, R), p("Mexico City", 19.433, -99.133, R),
            p("Sao Paulo", -23.551, -46.633, R), p("Buenos Aires", -34.604, -58.382, R), p("Beijing", 39.904, 116.407, R),
            p("Seoul", 37.567, 126.978, R), p("Taipei", 25.033, 121.565, R), p("Manila", 14.600, 120.984, R),
            p("Hanoi", 21.028, 105.854, R), p("Ho Chi Minh City", 10.823, 106.630, R), p("Phnom Penh", 11.556, 104.928, R),
            p("Yangon", 16.840, 96.173, R), p("Kabul", 34.555, 69.207, R), p("Tehran", 35.689, 51.389, R),
            p("Kinshasa", -4.441, 15.266, R), p("Luanda", -8.839, 13.289, R), p("Addis Ababa", 9.030, 38.740, R),
            p("Antananarivo", -18.879, 47.508, R), p("Moroni", -11.702, 43.256, R), p("Saint-Denis, Reunion", -20.882, 55.450, R),
            p("Caracas", 10.481, -66.904, R), p("Havana", 23.113, -82.366, R), p("San Juan", 18.466, -66.106, R),
            p("Port Vila", -17.734, 168.322, R), p("Noumea", -22.276, 166.458, R), p("Vladivostok", 43.116, 131.886, R),
        )
        assertTrue(cities.size >= 30)
        check(cities)
    }

    /** A pair of public points across each hard border the table's KDoc lists (the first left, the second right). */
    @Test
    fun eachHardBorderHasAPointOnEachSide() {
        val pairs = listOf(
            // Land borders, a few kilometres wide at most
            p("Hong Kong, Sheung Shui", 22.501, 114.128, L), p("Shenzhen, Luohu", 22.548, 114.131, R),
            p("Hong Kong, Central", 22.282, 114.158, L), p("Shenzhen, Futian", 22.541, 114.058, R),
            p("Hong Kong, Tuen Mun", 22.391, 113.977, L), p("Shenzhen, Shekou", 22.483, 113.917, R),
            p("Macau", 22.199, 113.544, L), p("Zhuhai", 22.271, 113.577, R),
            p("Albina", 5.4957, -54.0525, L), p("Saint-Laurent-du-Maroni", 5.4983, -54.0314, R),
            p("Mae Sai", 20.4335, 99.8761, L), p("Tachileik", 20.4475, 99.8836, R),
            p("Lethem", 3.3803, -59.7968, L), p("Bonfim", 3.3617, -59.8333, R),
            // Thailand with Myanmar, Laos, Cambodia
            p("Mae Sot", 16.713, 98.575, L), p("Myawaddy", 16.688, 98.508, R),
            p("Ranong", 9.966, 98.635, L), p("Kawthaung", 9.983, 98.550, R),
            p("Nong Khai", 17.878, 102.742, L), p("Vientiane", 17.975, 102.633, R),
            p("Aranyaprathet", 13.693, 102.503, L), p("Poipet", 13.659, 102.564, R),
            // Bangladesh and India with Myanmar
            p("Teknaf", 20.862, 92.304, L), p("Maungdaw", 20.817, 92.367, R),
            p("Imphal", 24.817, 93.937, L), p("Mandalay", 21.959, 96.089, R),
            // Nepal, Sikkim, Bhutan and Arunachal with China
            p("Kathmandu", 27.717, 85.324, L), p("Zhangmu", 27.990, 85.980, R),
            p("Gangtok", 27.339, 88.607, L), p("Yadong", 27.483, 88.908, R),
            p("Thimphu", 27.472, 89.639, L), p("Lhasa", 29.652, 91.172, R),
            p("Tawang", 27.586, 91.869, L), p("Cona", 27.992, 91.957, R),
            // Uganda with DR Congo, Rwanda, South Sudan; Tanzania with Burundi and Rwanda
            p("Kasese", 0.183, 30.083, L), p("Beni", 0.491, 29.473, R),
            p("Kisoro", -1.285, 29.685, L), p("Goma", -1.679, 29.222, R),
            p("Kabale", -1.249, 29.990, L), p("Kigali", -1.950, 30.059, R),
            p("Arua", 3.020, 30.911, L), p("Aru", 2.865, 30.838, R),
            p("Gulu", 2.775, 32.299, L), p("Juba", 4.859, 31.571, R),
            p("Kigoma", -4.877, 29.627, L), p("Bujumbura", -3.383, 29.361, R),
            // Zambia with DR Congo; Namibia and Zambia with Angola
            p("Ndola", -12.969, 28.637, L), p("Lubumbashi", -11.664, 27.479, R),
            p("Kitwe", -12.802, 28.213, L), p("Sakania", -12.748, 28.558, R),
            p("Solwezi", -12.173, 26.390, L), p("Kolwezi", -10.714, 25.467, R),
            p("Oshakati", -17.788, 15.704, L), p("Ondjiva", -17.067, 15.733, R),
            p("Rundu", -17.918, 19.766, L), p("Menongue", -14.658, 17.691, R),
            // Kenya with Ethiopia and Somalia
            p("Lodwar", 3.119, 35.597, L), p("Addis Ababa", 9.030, 38.740, R),
            p("Garissa", -0.453, 39.646, L), p("Kismayo", -0.358, 42.545, R),
            p("Mandera", 3.937, 41.856, L), p("Mogadishu", 2.046, 45.318, R),
            // Pakistan with Afghanistan and Iran; Pakistan and India with China
            p("Peshawar", 34.008, 71.578, L), p("Jalalabad", 34.431, 70.447, R),
            p("Chaman", 30.921, 66.451, L), p("Spin Boldak", 31.005, 66.400, R),
            p("Quetta", 30.180, 66.975, L), p("Kandahar", 31.613, 65.710, R),
            p("Gwadar", 25.126, 62.322, L), p("Chabahar", 25.292, 60.643, R),
            p("Gilgit", 35.920, 74.308, L), p("Kashgar", 39.470, 75.990, R),
            p("Leh", 34.153, 77.577, L), p("Shiquanhe", 32.503, 80.106, R),
            // Guyana and Suriname with Venezuela, Brazil and French Guiana
            p("Georgetown", 6.801, -58.155, L), p("Ciudad Guayana", 8.353, -62.641, R),
            p("Paramaribo", 5.852, -55.204, L), p("Cayenne", 4.922, -52.313, R),
            p("Linden", 6.000, -58.300, L), p("Boa Vista", 2.820, -60.672, R),
            // Islands across narrow water from a right-hand neighbour
            p("Dover", 51.128, 1.308, L), p("Calais", 50.951, 1.858, R),
            p("St Helier", 49.186, -2.107, L), p("Granville", 48.838, -1.597, R),
            p("Fukuoka", 33.590, 130.402, L), p("Busan", 35.180, 129.076, R),
            p("Izuhara, Tsushima", 34.203, 129.288, L), p("Jeju", 33.499, 126.531, R),
            p("Nemuro", 43.330, 145.583, L), p("Yuzhno-Kurilsk", 44.031, 145.862, R),
            p("Sandakan", 5.840, 118.117, L), p("Bongao", 5.029, 119.773, R),
            p("Charlotte Amalie", 18.343, -64.931, L), p("San Juan", 18.466, -66.106, R),
            p("Port of Spain", 10.654, -61.502, L), p("Guiria", 10.573, -62.299, R),
            p("Apia", -13.834, -171.760, L), p("Pago Pago", -14.279, -170.702, R),
            p("Suva", -18.124, 178.450, L), p("Mata-Utu", -13.282, -176.174, R),
            p("Honiara", -9.445, 159.973, L), p("Port Vila", -17.734, 168.322, R),
            p("Port Louis", -20.161, 57.501, L), p("Saint-Denis, Reunion", -20.882, 55.450, R),
            p("Limassol", 34.707, 33.022, L), p("Antalya", 36.897, 30.713, R),
            p("Valletta", 35.899, 14.514, L), p("Tripoli", 32.887, 13.191, R),
            p("Nassau", 25.048, -77.355, L), p("Havana", 23.113, -82.366, R),
            p("Koror", 7.341, 134.479, R), p("Kota Kinabalu", 5.980, 116.073, L),
        )
        check(pairs)
    }

    @Test
    fun theAreasReadTheirSideAtTheirCentre() {
        val areas = listOf(
            p("rainier", 46.855, -121.76, R), p("mont-blanc", 45.865, 6.865, R), p("bonneville", 40.75, -113.875, R),
            p("glacier-waterton", 49.0, -113.925, R),
            // everest straddles the Nepal-China border: the Khumbu glacier in Nepal, the East Rongbuk glacier in China.
            p("everest, Khumbu (Nepal)", 27.93, 86.87, L), p("everest, East Rongbuk (China)", 28.02, 86.92, R),
            p("aconcagua", -32.655, -70.015, R), p("denali", 63.06, -151.0, R), p("mauna-kea", 19.82, -155.47, R),
            p("north-slope", 69.95, -149.2, R), p("kona-lava-plain", 19.755, -156.03, R), p("kilimanjaro", -3.07, 37.355, L),
            p("svalbard", 78.195, 15.6, R), p("fiordland", -44.65, 167.89, L), p("taveuni", -16.84, 180.0, L),
            p("taveuni, west of 180", -16.84, 179.95, L), p("taveuni, east of 180", -16.84, -179.95, L),
            p("dead-sea", 31.46, 35.405, R), p("death-valley", 36.235, -116.78, R), p("flevoland", 52.515, 5.475, R),
            p("greenwich", 51.48, -0.005, L), p("mount-kenya", -0.09, 37.31, L), p("grand-canyon", 36.09, -112.115, R),
            p("table-mountain", -33.95, 18.42, L), p("torres-del-paine", -50.995, -73.08, R), p("cell-corner-46n8e", 46.0, 8.0, R),
            p("matterhorn", 45.975, 7.66, R), p("jungfrau", 46.54, 7.96, R), p("galdhopiggen", 61.635, 8.315, R),
            p("grossglockner", 47.075, 12.695, R), p("mount-robson", 53.105, -119.165, R), p("garibaldi", 49.85, -123.005, R),
            p("aoraki", -43.6, 170.145, L), p("rainier-lidar-edge", 46.755, -121.84, R), p("sea-tac", 47.4475, -122.3, R),
            p("pacific", 10.0, -140.0, R),
        )
        check(areas)
    }

    @Test
    fun aGivenSideIsKeptAndAFreeTableNeedsNone() {
        val given = TurnCost(DriveTurns, DrivingSide.RIGHT)
        assertSame(given, given.at(51.5, -0.1))
        val zero = TurnCost(TurnTable.ZERO)
        assertSame(zero, zero.at(51.5, -0.1))
        assertEquals(DrivingSide.LEFT, TurnCost().at(51.5, -0.1).side)
        assertEquals(DrivingSide.RIGHT, TurnCost().at(47.6, -122.3).side)
        assertEquals(null, TurnCost().side)
        assertEquals("left", DrivingSide.LEFT.key)
    }

    // ---- The side reaching the searches ----

    private val source = GraphSource("side-outdoors.pmtiles", "fp", "", 14, 0, drive = true)
    private val minor = WayAttrs(WayClass.MINOR, Surface.PAVED)

    /** A crossroads of unnamed minor roads at ([lat], [lon]), arms of 0.002 degrees. */
    private fun crossroads(lat: Double, lon: Double) = RoutingGraphBuilder().build(
        listOf(
            WayLine.of(minor, lat - 0.002 to lon, lat to lon, lat + 0.002 to lon),
            WayLine.of(minor, lat to lon - 0.002, lat to lon, lat to lon + 0.002),
        ),
        source,
    )

    private fun drive(g: RoutingGraph, a: Pair<Double, Double>, b: Pair<Double, Double>): PlannedRoute {
        val r = TileRouter(WholeGraph(g)).route(a.first, a.second, b.first, b.second, TravelMode.DRIVE)
        assertIs<RouteResult.Found>(r, "expected a route, got $r")
        return r.plan
    }

    @Test
    fun aDriveInALeftHandAreaPaysMoreForTheRightTurn() {
        // The same crossroads at the greenwich area (traffic keeps left) and at the rainier area (right).
        for ((lat, lon, side) in listOf(Triple(51.48, -0.005, L), Triple(46.855, -121.76, R))) {
            assertEquals(side, DrivingSides.at(lat - 0.001, lon), "drivingSide at the start")
            val g = crossroads(lat, lon)
            val south = lat - 0.001 to lon
            val right = drive(g, south, lat to lon + 0.001) // north, then right onto the east arm
            val left = drive(g, south, lat to lon - 0.001)
            val across = DriveTurns.ACROSS_90_S
            val kerb = DriveTurns.KERB_90_S
            assertEquals(1, right.turns)
            assertEquals(if (side == L) across else kerb, right.turnCostS!!, 0.2, "the right turn at $lat, $lon")
            assertEquals(if (side == L) kerb else across, left.turnCostS!!, 0.2, "the left turn at $lat, $lon")
        }
    }

    @Test
    fun anOverlayCellIsPricedWithTheSideAtItsCentre() {
        // In one graph tile: Main Road comes in across the tile's south border to a junction at the tile's centre, East
        // Road leaves it across the east border, and a minor spur goes north (three roads: the turn is priced). The cell's
        // shortcut from the south border node to the east one is a right turn: across traffic where traffic keeps left.
        val main = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "Main Road")
        val east = WayAttrs(WayClass.TERTIARY, Surface.PAVED, name = "East Road")
        val cost = HashMap<DrivingSide, Pair<Double, Double>>()
        for ((lat0, lon0, side) in listOf(Triple(51.48, -0.005, L), Triple(46.855, -121.76, R))) {
            val z = GraphTiler.ZOOM
            val x = WebMercator.lonToX(lon0, z).toInt()
            val y = WebMercator.latToY(lat0, z).toInt()
            val lat = WebMercator.yToLat(y + 0.5, z)
            val lon = WebMercator.xToLon(x + 0.5, z)
            val south = WebMercator.yToLat(y + 1.0, z)
            val eastEdge = WebMercator.xToLon(x + 1.0, z)
            val g = RoutingGraphBuilder().build(
                listOf(
                    WayLine.of(main, south - 0.01 to lon, lat to lon),
                    WayLine.of(east, lat to lon, lat to eastEdge + 0.01),
                    WayLine.of(minor, lat to lon, lat + 0.002 to lon),
                ),
                source,
            )
            val tg = TiledGraph(listOf(GraphTileFile.open(GraphTileFile.encode(g))))
            val piece = DriveOverlay().piece(x, y, tg.pieces(x, y))!!
            val cell = piece.overlay!!.cell!!
            val cg = cell.graph
            assertEquals(2, cg.nodeCount, "two border nodes")
            val s = (0 until 2).minBy { cg.nodeLat[it] }
            val toEast = (0 until cg.edgeCount).first { cg.edgeFrom[it] == s }
            val toSouth = (0 until cg.edgeCount).first { cg.edgeTo[it] == s }
            cost[side] = cell.cost[toEast] to cell.cost[toSouth]
        }
        val (lRight, lLeft) = cost.getValue(L)
        val (rRight, rLeft) = cost.getValue(R)
        val diff = CostModel(TravelMode.DRIVE).secondsCost(DriveTurns.ACROSS_90_S - DriveTurns.KERB_90_S)
        // Where traffic keeps left the right turn (south to east) costs more than the left one (east to south), by
        // the difference of the two turn values; where it keeps right, the other way round. The two cells differ in
        // length (the tiles are not the same size), so each is compared within itself.
        assertTrue(abs((lRight - lLeft) - diff) < 1e-6 * lRight + 0.5, "left-hand cell: right $lRight, left $lLeft, expected a difference of $diff")
        assertTrue(abs((rLeft - rRight) - diff) < 1e-6 * rRight + 0.5, "right-hand cell: right $rRight, left $rLeft, expected a difference of $diff")
    }
}
