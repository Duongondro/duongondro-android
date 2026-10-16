#!/usr/bin/env python3
"""Writes the export's words from iOS's Export.xcstrings, so both apps word the export alike.

    python3 Scripts/export-strings-from-ios.py [path/to/Export.xcstrings]

The catalogue defaults to ../duongondro-ios/App/Resources/Export.xcstrings next to the
repository (check out iOS's main there first). It writes app/src/main/res/values*/
strings_export.xml in every language the app has, and the key-to-resource map
app/src/main/kotlin/app/duongondro/data/ExportStrings.kt. Never edit those by hand: change
the catalogue on iOS, then run this again. ExportStringsTest checks the map covers every
key the PDF and the CSV use.

Android's changes to iOS's words: "iPhone" in the export line becomes "Android", and the
placeholders (%@, %lld, %1$@) become %1$s, %2$s (numbers come formatted, as text).
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_CATALOGUE = REPO.parent / "duongondro-ios" / "App" / "Resources" / "Export.xcstrings"
RES = REPO / "app" / "src" / "main" / "res"
KOTLIN = REPO / "app" / "src" / "main" / "kotlin" / "app" / "duongondro" / "data" / "ExportStrings.kt"

# The app's languages: resource folder for each catalogue language.
LANGUAGES = {
    "en": "values", "cs": "values-cs", "de": "values-de", "es": "values-es", "fr": "values-fr",
    "hu": "values-hu", "pl": "values-pl", "ru": "values-ru", "sk": "values-sk", "uk": "values-uk",
}

# Resource names that are not the key's slug (see slug()).
NAMES = {
    "%@ (continued)": "ex_continued",
    "Added %@": "ex_added_x",
    "Duongöndro: your data": "ex_title",
    "Every practice, session and streak, and everything the server keeps about your account. Your counts are decrypted on this phone and are readable in the file; the server never sees them. PDF to read and print, JSON for everything machine-readable, CSV as a ZIP with one table per kind of data.": "ex_footer",
    "Exported %@ · iPhone %@": "ex_exported",
    "No online account: everything below is on this phone only.": "ex_local_mode",
    "Nothing: this phone has no online account.": "ex_server_nothing",
    "Page %lld of %lld": "ex_page_n_of_n",
    "This phone has an account, but the server could not be reached, so this section and Friends hold only what the phone knows. Export again when you are connected.": "ex_server_unreachable",
    "The share sheet could not be shown. Try again.": "ex_share_failed",
    "Your account data could not be fetched from the server, so the export holds only what is on this phone.": "ex_server_missing",
    "%lld days": "ex_n_days",
    "%lld devices": "ex_n_devices",
    "%lld friends": "ex_n_friends",
    "%lld passkeys": "ex_n_passkeys",
    "%lld practices": "ex_n_practices",
    "%lld sessions": "ex_n_sessions",
    "%lld sealed sessions could not be opened on this phone, so they are only in the JSON export.": "ex_unreadable",
    # The privacy page's three lists (core's PdfReport.PRIVACY), in its order.
    "What is end-to-end encrypted": "ex_privacy_sealed_title",
    "These are sealed on your device before they are uploaded. The server stores them and cannot read them:": "ex_privacy_sealed_intro",
    "practice sessions, including the day, the duration and your notes": "ex_privacy_sealed_1",
    "counts, rounds and totals": "ex_privacy_sealed_2",
    "targets": "ex_privacy_sealed_3",
    "imported streak seeds": "ex_privacy_sealed_4",
    "The keys that open them stay on your devices. The server never receives a plaintext count or a key, and signing in does not give it one.": "ex_privacy_sealed_after",
    "What the server encrypts": "ex_privacy_encrypted_title",
    "The server has to read these to do its job, for example to show your name to your friends or to send a notification. It keeps them encrypted in its database, with a key held outside the database, so a copy of the database or a backup does not reveal them:": "ex_privacy_encrypted_intro",
    "your display name, and your grammatical gender if you give it": "ex_privacy_encrypted_1",
    "your e-mail address, if you sign in with one; an account with only a passkey has none": "ex_privacy_encrypted_2",
    "your username, if you chose one; it names your passkey and friends do not see it": "ex_privacy_encrypted_3",
    "the Apple or Google account your sign-in is linked to": "ex_privacy_encrypted_4",
    "your streaks, only for practices where you chose to publish them": "ex_privacy_encrypted_5",
    "the public keys of your account, your devices and your passkeys": "ex_privacy_encrypted_6",
    "push notification tokens": "ex_privacy_encrypted_7",
    "the text of any report you send": "ex_privacy_encrypted_8",
    "Unlike your practice data, these can be read by the running server while it works with them.": "ex_privacy_encrypted_after",
    "What the server stores in plain form": "ex_privacy_plain_title",
    "that your account exists, as a random number": "ex_privacy_plain_1",
    "which accounts are friends, and who invited, blocked, poked or reported whom, as links between those numbers": "ex_privacy_plain_2",
    "for each friend, whether you asked to hear when they practise": "ex_privacy_plain_3",
    "whether and when you set up a recovery key": "ex_privacy_plain_4",
    "which kinds of sign-in, notification service and device key storage you use, for example a passkey on an Android phone": "ex_privacy_plain_5",
    "for a streak you publish, when it runs out, so the server can remind you in time, and how many times it has been updated": "ex_privacy_plain_6",
    "when each thing was written and last changed, and how large each sealed blob is; for a practice session, also when your phone logged it and last changed or deleted it, by the phone's clock, even if it synced later": "ex_privacy_plain_7",
    "The last item is a real limit. These times and sizes can show when you practise, even for a private streak.": "ex_privacy_plain_limit",
    "Cover photos for practices never leave your phone.": "ex_privacy_plain_covers",
}

# Android's own, in the default language only.
FIXED = [
    ("ex_footer_url", "duongondro.app/privacy"),
    ("ex_dash", "–"),
]

PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?(@|lld)")


def slug(key):
    words = PLACEHOLDER.sub("", key).lower()
    return "ex_" + re.sub(r"[^a-z0-9]+", "_", words).strip("_")


def name(key):
    return NAMES.get(key) or slug(key)


def android_format(value, key):
    if key == "Exported %@ · iPhone %@":
        value = value.replace("iPhone", "Android")
    position = 0

    def placeholder(m):
        nonlocal position
        position += 1
        return f"%{m.group(1) or position}$s"

    value = PLACEHOLDER.sub(placeholder, value)
    value = value.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    value = value.replace("&", "&amp;").replace("<", "&lt;")
    if value[:1] in ("@", "?"):
        value = "\\" + value
    return value


def unit(entry, lang, key):
    """The catalogue's text for [lang]: a string, or {quantity: string} for a plural."""
    loc = entry.get("localizations", {}).get(lang)
    if loc is None:
        return key if lang == "en" else None
    if "variations" in loc:
        return {q: v["stringUnit"]["value"] for q, v in loc["variations"]["plural"].items()}
    return loc["stringUnit"]["value"]


QUANTITIES = ["zero", "one", "two", "few", "many", "other"]


def xml(catalogue, lang):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Settings › Your data › Export: the PDF, the CSV headers and the screen's words. Generated by",
        "     Scripts/export-strings-from-ios.py from duongondro-ios App/Resources/Export.xcstrings, so both",
        "     apps word the export alike; change the catalogue and run it again, never this file. -->",
        "<resources>",
    ]
    if lang == "en":
        for n, v in FIXED:
            lines.append(f'    <string name="{n}" translatable="false">{v}</string>')
    keys = sorted(catalogue)
    for key in keys:
        value = unit(catalogue[key], lang, key)
        if isinstance(value, dict):
            lines.append(f'    <plurals name="{name(key)}">')
            for q in QUANTITIES:
                if q in value:
                    lines.append(f'        <item quantity="{q}">{android_format(value[q], key)}</item>')
            lines.append("    </plurals>")
    for key in keys:
        value = unit(catalogue[key], lang, key)
        if isinstance(value, str):
            lines.append(f'    <string name="{name(key)}">{android_format(value, key)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kotlin(catalogue):
    words, plurals = [], []
    for key in sorted(catalogue):
        if isinstance(unit(catalogue[key], "en", key), dict):
            plurals.append(f"        {kotlin_string(key)} to R.plurals.{name(key)},")
        else:
            words.append(f"        {kotlin_string(key)} to R.string.{name(key)},")
    return "\n".join([
        "package app.duongondro.data",
        "",
        "import app.duongondro.R",
        "",
        "/**",
        " * iOS's Export.xcstrings keys (their English text) to the resources made from them.",
        " * Generated with strings_export.xml by Scripts/export-strings-from-ios.py; do not edit.",
        " */",
        "object ExportStrings {",
        "    val words: Map<String, Int> = mapOf(",
        *words,
        "    )",
        "",
        "    val plurals: Map<String, Int> = mapOf(",
        *plurals,
        "    )",
        "}",
        "",
    ])


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CATALOGUE
    catalogue = json.loads(path.read_text(encoding="utf-8"))["strings"]
    names = {}
    for key in catalogue:
        other = names.setdefault(name(key), key)
        if other != key:
            sys.exit(f"two keys make {name(key)}: {other!r} and {key!r}")
    for lang, folder in LANGUAGES.items():
        missing = [k for k in catalogue if unit(catalogue[k], lang, k) is None]
        if missing:
            sys.exit(f"{lang} lacks {len(missing)} strings, first {missing[0]!r}")
        (RES / folder / "strings_export.xml").write_text(xml(catalogue, lang), encoding="utf-8")
    KOTLIN.write_text(kotlin(catalogue), encoding="utf-8")
    print(f"{len(catalogue)} strings in {len(LANGUAGES)} languages")


if __name__ == "__main__":
    main()
