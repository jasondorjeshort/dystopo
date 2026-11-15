package io.github.jasondorjeshort.dystopo.routing

/** The side of the road traffic keeps to, which decides which turn crosses oncoming traffic ([Turn.acrossTraffic]). */
enum class DrivingSide(val key: String) {
    /** Traffic keeps right: a left turn crosses it. The default where the side is not known. */
    RIGHT("right"),
    /** Traffic keeps left: a right turn crosses it. */
    LEFT("left"),
}

/**
 * T283: the driving side at a point, from a hand-written table of coarse boxes for the territories where traffic keeps
 * left, written from the public list of such territories (no dataset or code copied, so no registry entry). Anywhere
 * no box covers reads [DrivingSide.RIGHT], the side of most of the world's roads. Routers read the side per way from
 * their data; offline, without a pack flag (a follow-up if a box proves wrong near a border), a short region list is the
 * cheap form (the values survey of wave 33).
 *
 * **Reading.** A point inside any [RIGHT] box (an exception: a right-hand neighbour a coarse left box would otherwise
 * swallow) is right; else inside any [LEFT] box, left; else right. Boxes are inclusive and in degrees; longitudes are
 * taken in [-180, 180), so a territory on the 180th meridian (Fiji) has a box on each side.
 *
 * **Where it is asked.** [TurnCost.at]: once per drive search, at the route's start ([TileRouter]), and once per
 * level-1 overlay cell, at the cell's centre ([DriveOverlay]), so a cached cell never depends on which route asked for
 * it. A route that crosses a side border prices its far-side turns by its start's side, except inside overlay cells,
 * which keep their own; no public pack of this project lies on such a border.
 *
 * **Accuracy.** Islands are boxed with sea to spare. Land borders between a left and a right territory are cut by box
 * edges, so the side is wrong in a band along them: about 1 to 3 km at Hong Kong and Shenzhen, Macau and Zhuhai, Albina
 * and Saint-Laurent-du-Maroni, Mae Sai and Tachileik, Lethem and Bonfim; about 5 to 15 km along Thailand's borders
 * with Myanmar, Laos and Cambodia, Bangladesh and India with Myanmar, Nepal, Sikkim and Bhutan with China, Uganda with
 * the Democratic Republic of the Congo and Rwanda, Zambia with the Democratic Republic of the Congo; about 15 to 40 km
 * along Pakistan with Afghanistan and Iran, Ladakh, Arunachal Pradesh and the north of India with China and Myanmar,
 * Angola with Namibia and Zambia, Kenya and Uganda with South Sudan, Ethiopia and Somalia, Tanzania with Rwanda and
 * Burundi, and Guyana and Suriname with Venezuela and Brazil. The test (`DrivingSideTest`) holds a pair of public points
 * on each side of each of these.
 */
object DrivingSides {
    /** One box: [side] from [south] to [north] and from [west] to [east] (degrees, inclusive, [west] <= [east]). */
    class Box(val name: String, val side: DrivingSide, val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }

    private fun l(name: String, s: Double, w: Double, n: Double, e: Double) = Box(name, DrivingSide.LEFT, s, w, n, e)
    private fun r(name: String, s: Double, w: Double, n: Double, e: Double) = Box(name, DrivingSide.RIGHT, s, w, n, e)

    /** Right-hand neighbours cut out of the [LEFT] boxes. */
    val RIGHT: List<Box> = listOf(
        r("Angola, the Kwando corner", -17.85, 21.0, -16.6, 23.3),
        r("DR Congo, Katanga north of Zambia", -11.2, 20.0, -8.0, 28.7),
        r("DR Congo, Lubumbashi", -12.0, 25.3, -11.2, 28.7),
        r("DR Congo, the Congo Pedicle", -12.8, 28.3, -12.0, 29.6),
        r("DR Congo, north of Lake Mweru", -8.55, 28.5, -8.0, 29.3),
        r("DR Congo, the south-west shore of Lake Tanganyika", -8.3, 29.3, -8.0, 30.7),
        r("China, the Chumbi valley", 27.25, 88.85, 28.4, 89.12),
        r("South Korea, Ulleungdo", 37.4, 130.75, 37.6, 131.0),
        r("South Korea, Dokdo", 37.2, 131.8, 37.3, 131.95),
        r("Russia, the southern Kuril Islands", 43.55, 145.25, 46.0, 150.0),
        r("Philippines, Sulu and Mindanao", 4.55, 119.25, 7.6, 127.0),
        r("Palau", 2.8, 131.0, 8.2, 135.0),
    )

    /** The territories that keep left. */
    val LEFT: List<Box> = listOf(
        // Europe
        l("Great Britain and Ireland, west of Greenwich", 49.8, -11.0, 61.0, 0.0),
        l("Great Britain, east of Greenwich", 51.5, 0.0, 61.0, 2.0),
        l("Great Britain, Kent and Sussex", 50.6, 0.0, 51.5, 1.47),
        l("Channel Islands", 49.15, -2.75, 49.75, -2.0),
        l("Malta", 35.78, 14.15, 36.1, 14.6),
        l("Cyprus", 34.5, 32.2, 35.75, 34.65),
        // Africa
        l("Southern Africa", -35.0, 11.5, -17.85, 41.0),
        l("Namibia, the north", -17.85, 13.0, -17.38, 18.5),
        l("Zambia, Zimbabwe, Malawi and Mozambique", -17.85, 22.0, -13.0, 41.0),
        l("Zambia, the north", -13.0, 24.0, -8.2, 33.8),
        l("Malawi, Mozambique and Tanzania, south", -13.0, 32.6, -9.3, 41.0),
        l("Tanzania", -11.8, 30.85, -2.45, 40.5),
        l("Tanzania, Lake Tanganyika north", -6.3, 29.55, -4.5, 30.85),
        l("Tanzania, Lake Tanganyika south", -8.0, 30.1, -6.3, 30.85),
        l("Tanzania, Kagera south", -2.45, 30.9, -1.6, 40.5),
        l("Tanzania, Kagera north", -1.6, 30.75, -1.0, 40.5),
        l("Uganda, West Nile and the north", 2.45, 30.88, 3.5, 35.05),
        l("Uganda, east of Lake Albert", -1.0, 31.3, 2.45, 35.05),
        l("Uganda, Rwenzori", -1.0, 29.75, 0.85, 31.3),
        l("Uganda, Kisoro and Kabale", -1.36, 29.6, -1.0, 30.15),
        l("Uganda, Moyo", 3.5, 31.5, 3.66, 32.0),
        l("Uganda, Karamoja", 3.5, 32.8, 4.0, 34.45),
        l("Kenya", -4.75, 34.0, 3.4, 40.95),
        l("Kenya, Kiunga", -2.0, 40.95, -1.2, 41.45),
        l("Kenya, Turkana north", 3.4, 34.0, 4.6, 36.2),
        l("Kenya, Ileret and Dukana", 3.4, 36.2, 3.95, 38.3),
        l("Kenya, north of Wajir", 3.4, 39.4, 3.85, 40.5),
        l("Kenya, Rhamu and Mandera", 3.4, 40.5, 3.98, 41.875),
        l("Mauritius", -20.6, 57.25, -19.9, 57.85),
        l("Mauritius, Rodrigues", -19.8, 63.3, -19.6, 63.55),
        l("Seychelles", -10.3, 45.9, -3.5, 56.5),
        l("Saint Helena", -16.05, -5.8, -15.88, -5.6),
        l("Ascension", -8.0, -14.45, -7.88, -14.3),
        // South Asia
        l("India, the south, Sri Lanka", 5.5, 68.0, 24.0, 92.22),
        l("Bangladesh, Teknaf", 20.6, 92.2, 21.05, 92.32),
        l("Bangladesh, the Chittagong hills", 21.05, 92.22, 22.5, 92.6),
        l("India, Mizoram and Tripura", 22.5, 88.0, 24.0, 93.2),
        l("India, Andaman and Nicobar", 6.5, 92.2, 13.8, 94.3),
        l("Maldives", -0.8, 72.5, 7.2, 73.9),
        l("Pakistan, the coast", 23.6, 61.75, 26.2, 71.2),
        l("India, Pakistan, Nepal and Bangladesh, the plains", 24.0, 66.3, 27.85, 88.2),
        l("Pakistan, Balochistan", 26.2, 63.3, 29.4, 71.6),
        l("Pakistan, Chagai", 28.5, 61.6, 29.4, 63.3),
        l("Pakistan, Quetta", 29.4, 66.3, 30.95, 71.6),
        l("Pakistan, Zhob", 30.0, 68.5, 31.5, 71.6),
        l("Pakistan, Qila Saifullah", 30.95, 67.0, 31.2, 68.5),
        l("Pakistan, Waziristan", 31.5, 69.3, 32.3, 71.6),
        l("Pakistan, Kurram to Peshawar", 32.3, 70.1, 34.05, 71.6),
        l("Pakistan, Mohmand and Bajaur", 34.05, 71.2, 34.9, 71.6),
        l("India and Pakistan, the north", 27.85, 71.6, 30.2, 80.25),
        l("India and Pakistan, Punjab and Uttarakhand", 30.2, 71.6, 31.0, 79.4),
        l("India, Kumaon", 30.2, 79.4, 30.55, 80.25),
        l("India and Pakistan, Himachal and Punjab", 31.0, 71.6, 32.5, 78.65),
        l("Kashmir, Gilgit-Baltistan and Ladakh", 32.5, 71.6, 35.8, 77.8),
        l("India, Ladakh, Pangong", 32.5, 77.8, 34.6, 78.8),
        l("India, Ladakh, Hanle", 32.5, 78.8, 33.4, 79.4),
        l("Pakistan, Chitral to Baltistan", 35.8, 71.6, 36.0, 76.0),
        l("Pakistan, upper Chitral", 36.0, 71.9, 36.4, 72.5),
        l("Pakistan, Gilgit", 36.0, 72.5, 36.55, 75.4),
        l("Pakistan, Hunza", 36.55, 73.0, 36.85, 75.4),
        l("Nepal, the west", 27.85, 80.25, 29.25, 84.2),
        l("Nepal, Humla", 29.25, 80.25, 30.2, 82.3),
        l("Nepal, Mugu and Dolpo", 29.25, 82.3, 29.6, 83.8),
        l("Nepal, Gorkha and Manang", 27.85, 84.2, 28.65, 85.35),
        l("Nepal, Langtang", 27.85, 85.35, 28.2, 85.95),
        l("Nepal, Khumbu", 27.85, 86.05, 28.0, 86.9),
        l("India, Sikkim", 27.85, 88.0, 28.13, 88.8),
        l("India, Assam, Meghalaya and Manipur", 24.0, 88.2, 26.0, 94.3),
        l("India and Bhutan, Nagaland", 26.0, 88.2, 27.1, 95.2),
        l("India and Bhutan, Sikkim to Arunachal", 27.1, 88.2, 27.85, 96.1),
        l("Bhutan, the north", 27.85, 89.2, 28.3, 91.4),
        l("India, Arunachal, Tawang to Lohit", 27.85, 92.4, 28.2, 97.15),
        l("India, Arunachal, Subansiri to Dibang", 28.2, 93.3, 28.7, 97.15),
        l("India, Arunachal, Siang", 28.7, 94.5, 29.2, 96.6),
        // East and South-East Asia, Oceania
        l("Thailand, the south", 5.6, 97.5, 9.8, 102.1),
        l("Thailand, Ranong to Chumphon", 9.8, 98.62, 10.6, 100.5),
        l("Thailand, the isthmus", 10.6, 99.2, 11.5, 100.5),
        l("Thailand, Prachuap", 11.5, 99.55, 12.6, 100.5),
        l("Thailand, the centre", 12.6, 99.15, 14.45, 102.52),
        l("Thailand, Trat", 11.6, 101.9, 12.6, 102.75),
        l("Thailand, the north-west", 14.45, 98.55, 17.5, 101.7),
        l("Thailand, Isan south", 14.45, 101.7, 15.5, 105.5),
        l("Thailand, Isan north", 15.5, 101.7, 16.5, 105.1),
        l("Thailand, Mukdahan", 16.5, 101.7, 17.5, 104.65),
        l("Thailand, Nong Khai", 17.5, 101.6, 17.885, 104.0),
        l("Thailand, Bueng Kan west", 17.885, 102.8, 18.05, 103.95),
        l("Thailand, Bueng Kan", 18.05, 103.3, 18.3, 103.95),
        l("Thailand, Nakhon Phanom north", 17.5, 104.0, 17.7, 104.5),
        l("Thailand, the north", 17.5, 97.9, 19.8, 100.9),
        l("Thailand, Nan", 18.0, 100.9, 19.5, 101.15),
        l("Thailand, Chiang Rai", 19.8, 99.2, 20.44, 100.35),
        l("Malaysia, Singapore, Brunei, Indonesia and Timor-Leste", -11.2, 95.0, 7.6, 141.0),
        l("Hong Kong, south of Yuen Long", 22.15, 113.82, 22.44, 114.45),
        l("Hong Kong, Yuen Long", 22.44, 113.97, 22.505, 114.15),
        l("Hong Kong, Sheung Shui to Sha Tau Kok", 22.44, 114.15, 22.545, 114.27),
        l("Macau", 22.105, 113.528, 22.215, 113.6),
        l("Japan, the Ryukyu Islands", 24.0, 122.9, 30.5, 131.5),
        l("Japan, Kyushu to Kanto", 30.5, 128.5, 34.0, 142.0),
        l("Japan, Tsushima", 34.05, 129.15, 34.75, 129.55),
        l("Japan, Honshu", 33.9, 130.8, 41.6, 142.2),
        l("Japan, Hokkaido", 41.3, 139.3, 45.6, 145.9),
        l("Japan, the Ogasawara Islands", 24.0, 141.0, 27.8, 142.4),
        l("Papua New Guinea", -11.7, 141.0, -0.8, 156.0),
        l("Solomon Islands", -12.4, 155.0, -5.0, 170.0),
        l("Nauru", -0.6, 166.85, -0.48, 166.98),
        l("Kiribati, the Gilbert Islands", -2.8, 172.5, 3.5, 177.0),
        l("Kiribati, Kiritimati", 1.6, -157.7, 2.1, -157.1),
        l("Tuvalu", -10.9, 176.0, -5.6, 179.95),
        l("Fiji, west of the 180th meridian", -21.0, 176.8, -12.4, 180.0),
        l("Fiji, east of the 180th meridian", -21.0, -180.0, -15.5, -178.3),
        l("Tonga", -22.5, -176.3, -15.5, -173.5),
        l("Samoa", -14.1, -172.85, -13.4, -171.3),
        l("Niue", -19.2, -170.0, -18.9, -169.7),
        l("Cook Islands", -22.2, -166.0, -8.8, -157.3),
        l("Australia", -44.0, 112.5, -9.0, 154.0),
        l("Australia, Cocos Islands", -12.3, 96.75, -11.8, 96.95),
        l("Australia, Norfolk Island", -29.15, 167.9, -28.98, 168.0),
        l("Australia, Lord Howe Island", -31.6, 159.0, -31.45, 159.15),
        l("New Zealand", -47.5, 166.0, -34.0, 178.9),
        l("New Zealand, the Chatham Islands", -44.5, -177.0, -43.6, -176.1),
        // The Americas
        l("Guyana, the north-west", 7.5, -60.0, 8.6, -58.0),
        l("Guyana, the centre", 5.2, -60.7, 7.5, -56.95),
        l("Guyana, the Rupununi", 2.3, -59.81, 5.2, -56.95),
        l("Suriname", 2.0, -56.95, 6.1, -54.45),
        l("Suriname, Albina", 5.3, -54.45, 6.1, -54.04),
        l("Falkland Islands", -52.5, -61.5, -50.9, -57.5),
        l("Jamaica", 17.6, -78.5, 18.6, -76.1),
        l("Cayman Islands", 19.2, -81.5, 19.8, -79.7),
        l("Bahamas, the north", 23.0, -79.6, 27.3, -74.0),
        l("Bahamas, Ragged Island and Long Island", 21.6, -76.0, 23.0, -74.5),
        l("Bahamas, Inagua and Mayaguana", 20.8, -74.5, 23.0, -72.6),
        l("Turks and Caicos Islands", 21.1, -72.6, 22.0, -71.0),
        l("Bermuda", 32.2, -64.95, 32.45, -64.6),
        l("Virgin Islands", 17.6, -65.1, 18.8, -64.2),
        l("Anguilla", 18.15, -63.2, 18.3, -62.9),
        l("Saint Kitts and Nevis", 17.08, -62.9, 17.42, -62.5),
        l("Antigua and Barbuda", 16.95, -62.0, 17.75, -61.6),
        l("Montserrat", 16.65, -62.25, 16.83, -62.13),
        l("Dominica", 15.18, -61.5, 15.65, -61.2),
        l("Saint Lucia", 13.7, -61.1, 14.12, -60.85),
        l("Saint Vincent and the Grenadines", 12.55, -61.5, 13.4, -61.1),
        l("Grenada", 11.95, -61.85, 12.55, -61.35),
        l("Barbados", 13.0, -59.7, 13.35, -59.4),
        l("Trinidad and Tobago", 10.0, -61.82, 11.4, -60.45),
    )

    /** The box that decides the side at ([lat], [lon]), or null where none covers it (right). */
    fun boxAt(lat: Double, lon: Double): Box? {
        val x = normLon(lon)
        return RIGHT.firstOrNull { it.contains(lat, x) } ?: LEFT.firstOrNull { it.contains(lat, x) }
    }

    /** The driving side at ([lat], [lon]). */
    fun at(lat: Double, lon: Double): DrivingSide = boxAt(lat, lon)?.side ?: DrivingSide.RIGHT

    /** [lon] in [-180, 180). */
    private fun normLon(lon: Double): Double {
        val x = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (x == -180.0 && lon > 0) 180.0 else x // 180 stays 180 (Fiji has a box on each side)
    }
}

/**
 * T283: this turn function with its side fixed where it is used: [TurnCost.side] when it was given, else the side at
 * ([lat], [lon]) ([DrivingSides.at]). A free table needs no side.
 */
fun TurnCost.at(lat: Double, lon: Double): TurnCost =
    if (side != null || free) this else TurnCost(table, DrivingSides.at(lat, lon), model)
