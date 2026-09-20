import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import test from 'node:test';

// Galin, 20.09, the point of the whole thing: older drivers cannot make out
// anything on the dark map and need the ordinary light OpenStreetMap one. That
// is a choice about the MAP, and has nothing to do with whether they want
// RoadCrew's panels or OsmAnd's. Tying the two together was the mistake these
// checks exist to prevent coming back.

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const STYLE = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewVisualStyle.java');
const HUD = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewNeonHud.java');
const BUTTON = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewStyleButton.java');

test('the map colours are their own setting, not a side effect of the panels', () => {
  assert.match(STYLE, /static boolean isDarkMap\(/, 'the map colour must be its own question');
  assert.match(STYLE, /static void setDarkMap\(/);
  const sync = STYLE.slice(STYLE.indexOf('public static boolean syncMapTheme('),
    STYLE.indexOf('public static boolean isNeonNight('));
  assert.match(sync, /if \(isDarkMap\(app\)\)/,
    'the map theme must follow the map colour choice');
  assert.doesNotMatch(sync, /isNeonBeta/,
    'and must not be decided by which panels are showing');
});

test('the bright route colour follows the dark map, not the panels', () => {
  const neonDay = STYLE.slice(STYLE.indexOf('public static boolean isNeonDay('),
    STYLE.indexOf('public static int getNeonDayRouteColor('));
  assert.match(neonDay, /isDarkMap\(context\)/);
  assert.doesNotMatch(neonDay, /isNeonBeta\(context\)/);
});

test('the light map is the plain automatic one, never a second dark look', () => {
  const restore = STYLE.slice(STYLE.indexOf('private static boolean restoreClassicTheme('),
    STYLE.indexOf('private static DayNightMode parseDayNightMode('));
  assert.match(restore, /setModeValue\(mode, DayNightMode\.AUTO\)/);
  assert.doesNotMatch(restore, /parseDayNightMode\(storedMode\)/,
    'a stored NIGHT must not be handed back - that is what made the switch look broken');
  const resets = restore.match(/resetModeToDefault\(mode\)/g) || [];
  assert.ok(resets.length >= 2, `neon's own route colour must be let go, found ${resets.length}`);
});

test('the two switches are separate actions', () => {
  const colours = HUD.slice(HUD.indexOf('static void toggleMapColours('),
    HUD.indexOf('static void toggleVisualStyle('));
  assert.match(colours, /setDarkMap\(/, 'the colour switch changes the map colour');
  assert.doesNotMatch(colours, /setNeonBeta\(/, 'and nothing about the panels');
  const panels = HUD.slice(HUD.indexOf('static void toggleVisualStyle('),
    HUD.indexOf('static void toggleVisualStyle(') + 500);
  assert.match(panels, /setNeonBeta\(/, 'the panel switch changes the panels');
  assert.doesNotMatch(panels, /setDarkMap\(/, 'and leaves the map colours alone');
});

test('the map-colour switch is in one place, the same in both looks', () => {
  // Galin, 20.09: "в който и да е режим да е все на едно място, за да може като
  // цъкне човека после пак да я търси там". So it is not in the neon header any
  // more - it is a map button above the green report one, shown in both looks.
  const header = HUD.slice(HUD.indexOf('private static LinearLayout createHeader('),
    HUD.indexOf('private static TextView notificationBadge('));
  assert.doesNotMatch(header, /toggleMapColours\(activity\)/,
    'the colour switch must have left the header, or it would move about');
  assert.match(header, /toggleVisualStyle\(activity\)/,
    'the panel switch stays in the header it belongs to');

  assert.match(BUTTON, /POS_BOTTOM/, 'same edge and corner as the report button');
  assert.match(BUTTON, /POS_RIGHT/);
  const show = BUTTON.slice(BUTTON.indexOf('protected boolean shouldShow('),
    BUTTON.indexOf('private ButtonPositionSize createDefaultPositionSize('));
  assert.match(show, /return mapColours \|\| !RoadCrewVisualStyle\.isNeonBeta/,
    'the colour button shows in both looks; the panel one only in classic');
});

test('the name reads as one word again', () => {
  // Galin, 20.09: taking the map-colour button out of the header is what makes
  // room for it - "хем за по-голямо удобство, хем името да ти бъде както си
  // беше преди". In portrait it had been breaking into "Road" / "Crew".
  const header = HUD.slice(HUD.indexOf('private static LinearLayout createHeader('),
    HUD.indexOf('private static TextView notificationBadge('));
  const brand = header.slice(header.indexOf('SpannableString brandText'),
    header.indexOf('TextView liveStatus'));
  assert.match(brand, /brand\.setMaxLines\(1\)/, 'the name must never wrap');
  assert.match(brand, /setAutoSizeTextTypeUniformWithConfiguration/,
    'and shrink rather than break if a phone is too narrow');
});

test('the right-hand column is one solid stack, lifted by one footer', () => {
  // Galin, 20.09, the whole point: zoom out, zoom in, the colour switch and the
  // report button are ONE column with nothing between them. The report button
  // used to take twice the lift - that extra button height was the gap in the
  // middle, and it pushed the top of the stack behind the header.
  const offsets = HUD.slice(HUD.indexOf('private static void applyNativeHudOffsets('),
    HUD.indexOf('public static Map<Integer, View> getRoutePreviewPanels('));
  assert.doesNotMatch(offsets, /zoomOffset \* 2/, 'no button gets a doubled lift');
  for (const id of ['map_zoom_in_button', 'map_zoom_out_button', 'roadcrew_report_button',
      'roadcrew_map_colours_button', 'roadcrew_panels_button']) {
    assert.ok(offsets.includes(`setTranslationY(mapHud.findViewById(R.id.${id}), zoomOffset)`),
      `${id} must take the same lift as the rest of the column`);
  }
});

test('nothing is hand-placed on the map any more', () => {
  // A column at a fixed 96dp from the top sat on the green report button in
  // landscape. OsmAnd's grid computes non-overlap; these buttons must be on it.
  assert.doesNotMatch(HUD, /floatingButton\(/,
    'the hand-placed column is what caused the overlap');
  assert.doesNotMatch(HUD, /CLASSIC_STYLE_BUTTON_TAG/);
  assert.match(BUTTON, /extends MapButton/,
    'only a real map button takes part in the grid');
  const layer = read('../../OsmAnd/src/net/osmand/plus/views/layers/MapControlsLayer.java');
  for (const layout of ['roadcrew_map_colours_button', 'roadcrew_panels_button']) {
    assert.ok(layer.includes(`addMapButton(createMapButton(inflater, R.layout.${layout}))`),
      `${layout} must be registered with the other map buttons`);
  }

  // The grid can only push a later button FURTHER from its anchor edge, so on
  // this bottom-anchored edge registration order IS the stacking order. Galin,
  // 20.09: the colour switch goes directly under the green report button, so it
  // must be registered before it - after it, the grid pushed it up into the
  // neon header.
  const colours = layer.indexOf('R.layout.roadcrew_map_colours_button');
  const report = layer.indexOf('R.layout.roadcrew_report_button');
  assert.ok(colours < report,
    'the colour switch must be registered before the report button, to sit below it');
});

test('the trip-approval map is the live map, so it cannot show other colours', () => {
  // Galin, 20.09: the look he picks has to hold on the approval screen too.
  // It does because that screen shows the navigation map itself - there is no
  // second map and no second setting. This check exists so a later "just set
  // the theme for this dialog" cannot quietly split them apart again.
  const dir = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
  const offenders = readdirSync(dir, { recursive: true })
    .filter((name) => /\.(java|kt)$/.test(name) && !name.endsWith('RoadCrewVisualStyle.java'))
    .filter((name) => /DAYNIGHT_MODE/.test(readFileSync(new URL(name, dir), 'utf8')));
  assert.deepEqual(offenders, [],
    'only RoadCrewVisualStyle may decide the map colours');

  const review = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java');
  assert.match(review, /activity\.getMapView\(\)/,
    'the approval screen must drive the real map view');
});
