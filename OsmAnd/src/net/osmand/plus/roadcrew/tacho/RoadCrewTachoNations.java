package net.osmand.plus.roadcrew.tacho;

/**
 * NationNumeric of the card's places: the official table of the JRC, the laboratory appointed
 * for tachograph interoperability (dtc.jrc.ec.europa.eu/dtc_nation_codes.php.html, read
 * 09.10.2026, ROADMAP 381). A reserved code stays unknown - never guessed. Plain Java.
 */
public final class RoadCrewTachoNations {

	/** code, alpha, Bulgarian, English. */
	private static final String[][] TABLE = {
			{"00", " ", "Няма данни", "No information"},
			{"01", "A", "Австрия", "Austria"},
			{"02", "AL", "Албания", "Albania"},
			{"03", "AND", "Андора", "Andorra"},
			{"04", "ARM", "Армения", "Armenia"},
			{"05", "AZ", "Азербайджан", "Azerbaijan"},
			{"06", "B", "Белгия", "Belgium"},
			{"07", "BG", "България", "Bulgaria"},
			{"08", "BIH", "Босна и Херцеговина", "Bosnia and Herzegovina"},
			{"09", "BY", "Беларус", "Belarus"},
			{"0A", "CH", "Швейцария", "Switzerland"},
			{"0B", "CY", "Кипър", "Cyprus"},
			{"0C", "CZ", "Чехия", "Czech Republic"},
			{"0D", "D", "Германия", "Germany"},
			{"0E", "DK", "Дания", "Denmark"},
			{"0F", "E", "Испания", "Spain"},
			{"10", "EST", "Естония", "Estonia"},
			{"11", "F", "Франция", "France"},
			{"12", "FIN", "Финландия", "Finland"},
			{"13", "FL", "Лихтенщайн", "Liechtenstein"},
			{"14", "FR", "Фарьорски острови", "Faroe Islands"},
			{"15", "UK", "Обединено кралство", "United Kingdom"},
			{"16", "GE", "Грузия", "Georgia"},
			{"17", "GR", "Гърция", "Greece"},
			{"18", "H", "Унгария", "Hungary"},
			{"19", "HR", "Хърватия", "Croatia"},
			{"1A", "I", "Италия", "Italy"},
			{"1B", "IRL", "Ирландия", "Ireland"},
			{"1C", "IS", "Исландия", "Iceland"},
			{"1D", "KZ", "Казахстан", "Kazakhstan"},
			{"1E", "L", "Люксембург", "Luxembourg"},
			{"1F", "LT", "Литва", "Lithuania"},
			{"20", "LV", "Латвия", "Latvia"},
			{"21", "M", "Малта", "Malta"},
			{"22", "MC", "Монако", "Monaco"},
			{"23", "MD", "Молдова", "Moldova"},
			{"24", "MK", "Северна Македония", "North Macedonia"},
			{"25", "N", "Норвегия", "Norway"},
			{"26", "NL", "Нидерландия", "Netherlands"},
			{"27", "P", "Португалия", "Portugal"},
			{"28", "PL", "Полша", "Poland"},
			{"29", "RO", "Румъния", "Romania"},
			{"2A", "RSM", "Сан Марино", "San Marino"},
			{"2B", "RUS", "Русия", "Russia"},
			{"2C", "S", "Швеция", "Sweden"},
			{"2D", "SK", "Словакия", "Slovakia"},
			{"2E", "SLO", "Словения", "Slovenia"},
			{"2F", "TM", "Туркменистан", "Turkmenistan"},
			{"30", "TR", "Турция", "Türkiye"},
			{"31", "UA", "Украйна", "Ukraine"},
			{"32", "V", "Ватикан", "Vatican City"},
			{"33", "YU", "Югославия", "Yugoslavia"},
			{"34", "MNE", "Черна гора", "Montenegro"},
			{"35", "SRB", "Сърбия", "Serbia"},
			{"36", "UZ", "Узбекистан", "Uzbekistan"},
			{"37", "TJ", "Таджикистан", "Tajikistan"},
			{"38", "KG", "Киргизстан", "Kyrgyz Republic"},
			{"39", "IL", "Израел", "Israel"},
			{"FD", "EC", "Европейска общност", "European Community"},
			{"FE", "EUR", "Останалата част на Европа", "Rest of Europe"},
			{"FF", "WLD", "Останалата част на света", "Rest of the World"},
	};

	private RoadCrewTachoNations() {
	}

	private static String[] row(int code) {
		String hex = String.format("%02X", code & 0xFF);
		for (String[] r : TABLE) {
			if (r[0].equals(hex)) {
				return r;
			}
		}
		return null;
	}

	public static String alpha(int code) {
		String[] r = row(code);
		return r == null ? "?" : r[1];
	}

	public static String bulgarian(int code) {
		String[] r = row(code);
		return r == null ? "Код " + (code & 0xFF) : r[2];
	}

	public static String english(int code) {
		String[] r = row(code);
		return r == null ? "Code " + (code & 0xFF) : r[3];
	}
}
