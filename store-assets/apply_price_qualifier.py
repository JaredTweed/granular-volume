"""
Applies the Pro-launch price qualifier across the site, surgically.

WHY A SCRIPT AND NOT A SED: of the price-word hits on the site, a large share must NOT
change. "free software under the GNU GPL" is libre, not price, and editing it would be a
licensing error. "the free fixes" and "both are free and built in" describe the reader's
own device settings, not our app. A blanket replace breaks all of those.

So every replacement below is an EXACT, whole-phrase match. Anything that does not match
is left alone and reported, which means a phrasing that changed since this was written
gets flagged rather than silently missed.

THE RULE THIS ENCODES: strip the bare price word from short taglines, where a qualifier
cannot fit and would read as a trial, and state the boundary properly where there is room
for a sentence. Every attribute that survives (no ads, no tracking, no account, no
internet, open source) stays true at any price, forever.

Run from the repo root:  python store-assets/apply_price_qualifier.py
Add --check to report without writing.
"""
import glob
import io
import os
import sys

CHECK_ONLY = "--check" in sys.argv

# (exact old, new, human label). Order matters: longest/most specific first.
REPLACEMENTS = [
    # --- The FAQ answer, visible text and its JSON-LD twin. The 2026-08-02 failure was
    # --- fixing one and forgetting the other, so both forms are listed explicitly.
    (
        "Yes. Free, no ads, no account, no in-app purchases, and no internet permission at all, "
        "so it cannot send data anywhere. It is open source under GPL-3.0.",
        "Yes. Free to install, with volume control across the normal range and the first step "
        "below your device minimum free permanently; the deeper steps are a one-time purchase. "
        "No ads, no account, no in-app purchases, and no internet permission at all, so it "
        "cannot send data anywhere. Open source under GPL-3.0, and the F-Droid build has every "
        "step at no charge.",
        "FAQ answer (JSON-LD)",
    ),
    (
        "Yes. Free, no ads, no account, no in-app purchases, and no internet permission at all, "
        "so it cannot send data anywhere. Open source under GPL-3.0.",
        "Yes. Free to install, with volume control across the normal range and the first step "
        "below your device minimum free permanently; the deeper steps are a one-time purchase. "
        "No ads, no account, no in-app purchases, and no internet permission at all, so it "
        "cannot send data anywhere. Open source under GPL-3.0, and the F-Droid build has every "
        "step at no charge.",
        "FAQ answer (visible)",
    ),
    (
        "Yes. Free, no ads, no account, and no internet permission at all, so it cannot send "
        "data anywhere. It is open source under GPL-3.0.",
        "Yes. Free to install, with volume control across the normal range and the first step "
        "below your device minimum free permanently; the deeper steps are a one-time purchase. "
        "No ads, no account, and no internet permission at all, so it cannot send data "
        "anywhere. Open source under GPL-3.0, and the F-Droid build has every step at no charge.",
        "FAQ answer (short variant)",
    ),

    # --- Taglines. No room for a qualifier, and a half-qualifier reads as a trial, so the
    # --- price word comes out and the permanent attributes stay.
    ("Free, open source, no ads, no tracking, no account, and no internet permission",
     "Open source, no ads, no tracking, no account, and no internet permission", "tagline"),
    ("Free, open source, no ads, no tracking, no account, no internet",
     "Open source, no ads, no tracking, no account, no internet", "tagline"),
    ("Free, open source, no ads, no tracking, no account",
     "Open source, no ads, no tracking, no account", "tagline"),
    ("Free, open source, no ads, no tracking", "Open source, no ads, no tracking", "tagline"),
    ("Free, open source under GPL-3.0, no ads", "Open source under GPL-3.0, no ads", "tagline"),
    ("Free, open source, no ads", "Open source, no ads", "tagline"),
    ("Free, no root, set up in a minute", "No root, set up in a minute", "tagline"),
    ("Free, no root, no ads", "No root, no ads", "tagline"),

    # --- One more FAQ variant, on the broken-buttons page, in both its forms.
    ("Yes. Free, no ads, no account, and no internet permission at all, so it cannot send "
     "data anywhere. Open source under GPL-3.0.",
     "Yes. Free to install, with volume control across the normal range and the first step "
     "below your device minimum free permanently; the deeper steps are a one-time purchase. "
     "No ads, no account, and no internet permission at all, so it cannot send data anywhere. "
     "Open source under GPL-3.0, and the F-Droid build has every step at no charge.",
     "FAQ answer (buttons page)"),

    # --- The homepage chip row and the feature heading. A one-word chip cannot carry a
    # --- qualifier, so the price word goes and the permanent attributes stay, exactly as
    # --- was done on the store screenshots.
    ('<span class="chip">Free</span>', '', "homepage chip"),
    ("<h3>Free and private</h3>", "<h3>Private by design</h3>", "homepage feature heading"),

    # --- The comparison row. Claiming a price advantage over "often paid" alternatives is
    # --- exactly the claim that stops being clean the day we start charging for a tier.
    ("Free, open source, no tracking", "Open source, no ads, no tracking", "comparison row"),

    # --- German and Spanish. An English-only pass misses these entirely, which is why the
    # --- sweep is re-run after every patch instead of trusted once.
    ("Ohne Root, kostenlos", "Ohne Root", "tagline (de)"),
    ("Kostenlos im Play Store", "Im Play Store", "CTA button (de)"),
    ("Kostenlos, quelloffen, ohne Werbung, ohne Konto und ohne Internet-Berechtigung.",
     "Quelloffen, ohne Werbung, ohne Konto und ohne Internet-Berechtigung.", "tagline (de)"),
    ("Gratis en Play Store", "En Play Store", "CTA button (es)"),
    ("Gratis, código abierto, sin anuncios, sin cuenta y sin permiso de internet.",
     "Código abierto, sin anuncios, sin cuenta y sin permiso de internet.", "tagline (es)"),

    # --- THE MOST DANGEROUS ONE ON THE SITE. A structured-data FAQ that asks, in so many
    # --- words, whether there is a paid tier, and answers "entirely free". Google can
    # --- surface this as a rich result, so it is the answer a searcher sees without ever
    # --- opening the page. It sits on the FLAGSHIP guide.
    ('"As of this writing it\'s entirely free, with no in-app purchases, subscriptions, or ads."',
     '"The app is free to install, and volume control across the normal range plus the first '
     'step below your device minimum are free permanently. The deeper quiet steps are a '
     'one-time purchase of a separate unlock app. There are no in-app purchases, no '
     'subscriptions and no ads, and the F-Droid build includes every step at no charge."',
     "paid-tier FAQ (JSON-LD, flagship page)"),

    ("this is the fix, free and set up in under a minute",
     "this is the fix, and it is set up in under a minute", "inline claim"),

    # --- Store description, which feeds BOTH Play and F-Droid from the same file.
    ("FREE, PRIVATE, OPEN SOURCE\nNo ads. No tracking.",
     "PRIVATE, OPEN SOURCE\nFree to install. Volume control across the normal range and the "
     "first step below your device minimum are free, permanently; the deeper quiet steps are "
     "a one-time purchase. No ads. No tracking.", "store description"),
    # ---- ROUND 6 -------------------------------------------------------------------
    # The visible twin of the flagship "entirely free" answer. The JSON-LD copy was fixed
    # first; this is the same sentence rendered for humans, and missing it would have been
    # the 2026-08-02 failure exactly.
    ("As of this writing it's entirely free, with no in-app purchases, subscriptions, or ads.",
     "The app is free to install, and volume control across the normal range plus the first "
     "step below your device minimum are free permanently. The deeper quiet steps are a "
     "one-time purchase of a separate unlock app. There are no in-app purchases, no "
     "subscriptions and no ads, and the F-Droid build includes every step at no charge.",
     "paid-tier FAQ (visible twin)"),

    # Samsung page, its own FAQ wording, both forms.
    ("It is free with no ads, no account, and no in-app purchases.",
     "It is free to install, with volume control across the normal range and the first step "
     "below your device minimum free permanently and the deeper steps available as a one-time "
     "purchase. No ads, no account, no in-app purchases.",
     "FAQ answer (samsung page)"),
    ("Free, no ads, no account, no in-app purchases. It has no internet permission at all,",
     "Free to install, with the deeper quiet steps available as a one-time purchase. No ads, "
     "no account, no in-app purchases. It has no internet permission at all,",
     "FAQ answer (samsung visible)"),

    # Tinnitus page.
    ("Free, no ads, no account, no in-app purchases, and no internet permission at all. "
     "Open source under GPL-3.0.",
     "Free to install, with the deeper quiet steps available as a one-time purchase. No ads, "
     "no account, no in-app purchases, and no internet permission at all. Open source under "
     "GPL-3.0, and the F-Droid build has every step at no charge.",
     "FAQ answer (tinnitus page)"),
    ("Free, no ads, no account, no internet permission.",
     "No ads, no account, no internet permission.", "tagline"),

    # Short CTA lines with no room for a qualifier.
    ("Free, and set up in under a minute.", "Set up in under a minute.", "tagline"),
    ("Free, no root<", "No root<", "tagline"),
    # Pixel page, the line that closes the description of OUR app. Note the near-identical
    # "Free, instant, reversible" a few paragraphs above is about the reader's own hearing
    # aid setting, not about us, and must be left exactly as it is.
    ("on those calls are not affected. Free, no root.",
     "on those calls are not affected. No root.", "tagline (pixel page)"),

    # The localized store descriptions. English was fixed in round 5; these three were not,
    # and no English-shaped pattern would ever have reached them.
    ("KOSTENLOS, PRIVAT, OPEN SOURCE\nKeine Werbung.",
     "PRIVAT, OPEN SOURCE\nKostenlos zu installieren. Die Lautstaerkeregelung im normalen "
     "Bereich und die erste Stufe unter dem Geraeteminimum sind dauerhaft kostenlos; die "
     "tieferen Stufen sind ein einmaliger Kauf. Keine Werbung.", "store description (de)"),
    ("GRATIS, PRIVADO, C\u00d3DIGO ABIERTO\nSin anuncios.",
     "PRIVADO, C\u00d3DIGO ABIERTO\nGratis de instalar. El control de volumen en el rango normal "
     "y el primer paso por debajo del m\u00ednimo de tu dispositivo son gratuitos de forma "
     "permanente; los pasos m\u00e1s profundos son una compra \u00fanica. Sin anuncios.",
     "store description (es)"),
    ("GRATUIT, PRIV\u00c9, OPEN SOURCE\nPas de publicit\u00e9.",
     "PRIV\u00c9, OPEN SOURCE\nInstallation gratuite. Le r\u00e9glage du volume dans la plage "
     "normale et le premier palier sous le minimum de votre appareil sont gratuits en "
     "permanence; les paliers plus bas sont un achat unique. Pas de publicit\u00e9.",
     "store description (fr)"),

]

# Phrases that must survive untouched. Verified after every run.
MUST_SURVIVE = [
    "free software under the",     # the GPL statement: libre, not price
    "free fixes",                  # the reader's own device settings, not our app
    "free and built in",           # OEM features
]


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)
    targets = (sorted(glob.glob("docs/**/*.html", recursive=True)) + ["README.md"]
               + sorted(glob.glob("fastlane/metadata/android/*/full_description.txt")))

    total = 0
    per_label = {}
    touched = []
    for path in targets:
        if not os.path.exists(path):
            continue
        s = original = io.open(path, encoding="utf-8").read()
        for old, new, label in REPLACEMENTS:
            n = s.count(old)
            if n:
                s = s.replace(old, new)
                total += n
                per_label[label] = per_label.get(label, 0) + n
        if s != original:
            touched.append(path)
            if not CHECK_ONLY:
                io.open(path, "w", encoding="utf-8", newline="\n").write(s)

    print("replacements: %d across %d files%s"
          % (total, len(touched), "  (check only, nothing written)" if CHECK_ONLY else ""))
    for label, n in sorted(per_label.items(), key=lambda kv: -kv[1]):
        print("   %-24s %d" % (label, n))

    print("\nphrases that must survive, still present:")
    for phrase in MUST_SURVIVE:
        found = sum(io.open(p, encoding="utf-8").read().count(phrase)
                    for p in targets if os.path.exists(p))
        print("   %-28s %d" % (phrase, found))


if __name__ == "__main__":
    main()
