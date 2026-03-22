import org.ossreviewtoolkit.evaluator.rules.*
import org.ossreviewtoolkit.model.*

licenseRule("CopyleftStrong") {
    require {
        -isExcluded()
    }

    val forbidden = setOf(
        "GPL-1.0-only",
        "GPL-1.0-or-later",
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-3.0-only",
        "GPL-3.0-or-later",
        "AGPL-1.0-only",
        "AGPL-1.0-or-later",
        "AGPL-3.0-only",
        "AGPL-3.0-or-later"
    )

    issue(
        Severity.ERROR,
        "Forbidden copyleft license detected.",
        "Review the dependency and replace it or add a documented exception only if legally justified."
    ) {
        license.toString() in forbidden
    }
}

licenseRule("CopyleftWeak") {
    require {
        -isExcluded()
    }

    val review = setOf(
        "LGPL-2.0-only",
        "LGPL-2.0-or-later",
        "LGPL-2.1-only",
        "LGPL-2.1-or-later",
        "LGPL-3.0-only",
        "LGPL-3.0-or-later",
        "MPL-2.0"
    )

    issue(
        Severity.WARNING,
        "License requires manual review.",
        "Check whether the usage pattern is acceptable for your distribution model."
    ) {
        license.toString() in review
    }
}
