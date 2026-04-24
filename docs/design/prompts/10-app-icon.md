# Asset — App icon (T1-7, pure-symbol mark)

## 1. GOAL

Design a launcher icon for Kuira that works at every
Android adaptive-icon size (48dp to 108dp), follows the Dusk palette,
and conveys "privacy + blockchain" without text.

## 2. SITEMAP POSITION

- `from:` Android launcher · Recent apps · notification tray
- `to:` App launch → Splash (11) → Main tabs (My Sigil home)

## 3. STATES

n/a — static asset, no runtime states.

| State           | Applies?  | Notes     |
|-----------------|-----------|-----------|
| `default`       | ✓         | The icon  |
| `loading-first` | n/a       |           |
| `syncing`       | n/a       |           |
| `empty`         | n/a       |           |
| `no-results`    | n/a       |           |
| `error`         | n/a       |           |
| `offline`       | n/a       |           |
| `pending`       | n/a       |           |
| `success`       | n/a       |           |

## 4. LAYOUT

### Adaptive icon layers

```
[Background layer]  108×108dp (Android full bleed)
  Solid fill: Void (0xFF000000)

[Foreground layer]  108×108dp
  [Symbol]  centered in the 66dp safe zone (Android's inner circle)
            pure white (#FFFFFF) on transparent
            24dp stroke weight for legibility at small sizes
```

### Symbol concepts (pick one)

The symbol must communicate at 48dp launcher size — test at that
scale before refining detail. Concepts to explore:

1. **Shield + star** — a minimalist shield outline (privacy) with a
   single star dot inside (the Midnight star-field brand signature)
2. **K monogram** — geometric "K" constructed from straight lines,
   with one arm extending into a star point
3. **Midnight circle** — crescent or eclipse shape (midnight = the
   darkest hour), single star dot at the apex
4. **Abstract key** — simplified key silhouette (wallet = access),
   head of the key is a star/dot

### Wordmark placement (NOT on launcher icon)

The wordmark "KUIRA" appears on:
- Splash screen (11) — large, centered
- Play Store feature graphic — alongside the symbol
- Marketing materials

It does NOT appear on the launcher icon — wordmarks don't survive
adaptive-icon masking (circle, squircle, rounded square all crop
differently).

## 5. INTERACTIONS

n/a — static asset.

## 6. MOTION

n/a for the icon itself. The splash screen (11) animates the symbol.

## 7. HAPTICS

n/a — static asset.

## 8. COPY

n/a — no text in the icon. App label shown by the launcher is `Kuira`
(set in `AndroidManifest.xml` `android:label`).

## 9. A11Y

- Content description for the launcher icon: `Kuira`
  (set via `android:icon` accessibility attributes in the manifest).

## 10. VISUAL LOCKED

- Dusk palette ONLY: Void background (#000000), Light foreground
  (#FFFFFF). No grey, no gradient, no accent color.
- Pure symbol — no text, no wordmark, no denomination.
- Must read at 48dp (smallest launcher icon size). If a concept
  doesn't survive that scale, it's wrong.
- Adaptive-icon safe zone is the inner 66dp circle of the 108dp
  canvas. The symbol must fit entirely within this zone.
- No shadows, no drop-shadows, no emboss. Flat white on flat black.
- The icon must look intentional next to Phantom, MetaMask, Lace,
  Trust Wallet on a user's home screen. Test in context, not in
  isolation.

## 11. PRODUCT LOCKED

- AI-generated draft (Midjourney/DALL-E/Firefly) → engineer
  refinement in Figma/Inkscape (per T1-7 decision).
- Symbol chosen must also work as a monochrome notification icon
  (white silhouette on transparent).
- Symbol must NOT resemble existing wallet icons (Phantom's ghost,
  MetaMask's fox, Lace's diamond, Trust's shield).

## 12. NEW COMPONENTS

n/a — this is an asset deliverable, not a runtime component.

**Deliverables:**
- `res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon manifest)
- `res/drawable/ic_launcher_foreground.xml` (vector foreground layer)
- `res/drawable/ic_launcher_background.xml` (solid Void fill)
- `res/mipmap-*dpi/ic_launcher.png` (rasterized fallbacks for pre-26)
- `res/drawable/ic_launcher_monochrome.xml` (themed icon, API 33+)

---

End of App icon spec. Deliverable is the adaptive icon asset set,
NOT wireframe frames.
