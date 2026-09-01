package ph.bulig.data.presentation

/**
 * The emergency types the app can file, bundled with the build.
 *
 * Bundled, not fetched. A resident's first launch may well be during the
 * disaster, on a phone that has never had signal since install — a type list
 * behind a network call would make the app useless at exactly the moment it
 * matters. The server's list is authoritative for the *command center*; this is
 * what the phone can offer when it is alone.
 *
 * These entries mirror `backend/database/seeders/EmergencyTypeSeeder.php`
 * exactly. `EmergencyTypeCatalogTest` pins the codes and severities, so a change
 * on one side without the other fails a test rather than silently scoring a
 * report differently on the phone than on the server.
 *
 * TO BE VALIDATED: the Waray-Waray strings are the handoff's reviewed
 * placeholders. At least three read as Cebuano or Hiligaynon rather than
 * Waray-Waray — see `docs/design/COPY-REVIEW.md`. A native speaker must check
 * every one before the pilot.
 */
object EmergencyTypeCatalog {

    val all: List<EmergencyTypeOption> = listOf(
        EmergencyTypeOption("MEDICAL", "Medical", "Emerhensya Medikal", "medical_services", 35, true),
        EmergencyTypeOption("FIRE", "Fire", "Sunog", "local_fire_department", 40, true),
        EmergencyTypeOption("FLOOD", "Flood", "Baha", "flood", 30, false),
        EmergencyTypeOption("LANDSLIDE", "Landslide", "Pagdahili sang tuna", "landslide", 35, true),
        EmergencyTypeOption("EARTHQUAKE", "Earthquake", "Linog", "earthquake", 35, true),
        EmergencyTypeOption("RESCUE", "Rescue needed", "Kinahanglan bulig", "hail", 30, false),
        EmergencyTypeOption("MISSING", "Missing person", "Nawawara nga tawo", "person_search", 25, false),
        EmergencyTypeOption("TRAPPED", "Trapped person", "Nakukulong nga tawo", "emergency_home", 40, true),
        EmergencyTypeOption("INFRA", "Infrastructure", "Nadaot nga pasilidad", "construction", 15, false),
        EmergencyTypeOption("OTHER", "Other", "Iba pa", "more_horiz", 10, false),
    )

    fun byCode(code: String): EmergencyTypeOption? = all.firstOrNull { it.code == code }
}
