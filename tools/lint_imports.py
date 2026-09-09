"""Cheap pre-compile check: common Compose/Kotlin symbols used without their import.

Run from the repo root:  python tools/lint_imports.py
Not a compiler substitute; it catches the mistakes that are easiest to make when
writing many files by hand (missing icon imports, `by remember` without getValue...).
"""
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "kotlin")

SIMPLE = [
    (r"\bstringResource\(", "androidx.compose.ui.res.stringResource"),
    (r"\bpluralStringResource\(", "androidx.compose.ui.res.pluralStringResource"),
    (r"\bR\.(string|plurals|drawable)\.", "com.rommmobile.app.R"),
    (r"collectAsStateWithLifecycle\(", "androidx.lifecycle.compose.collectAsStateWithLifecycle"),
    (r"\.dp\b", "androidx.compose.ui.unit.dp"),
    (r"\b\d+\.sp\b", "androidx.compose.ui.unit.sp"),
    (r"\bModifier\b", "androidx.compose.ui.Modifier"),
    (r"\bremember\s*[({<]", "androidx.compose.runtime.remember"),
    (r"\bmutableStateOf\(", "androidx.compose.runtime.mutableStateOf"),
    (r"\bmutableIntStateOf\(", "androidx.compose.runtime.mutableIntStateOf"),
    (r"\bLaunchedEffect\(", "androidx.compose.runtime.LaunchedEffect"),
    (r"\bDisposableEffect\(", "androidx.compose.runtime.DisposableEffect"),
    (r"\bIcon\(", "androidx.compose.material3.Icon"),
    (r"\bIconButton\(", "androidx.compose.material3.IconButton"),
    (r"\bButton\(", "androidx.compose.material3.Button"),
    (r"\bOutlinedButton\(", "androidx.compose.material3.OutlinedButton"),
    (r"\bTextButton\(", "androidx.compose.material3.TextButton"),
    (r"\bMaterialTheme\.", "androidx.compose.material3.MaterialTheme"),
    (r"\bColumn\(", "androidx.compose.foundation.layout.Column"),
    (r"\bRow\(", "androidx.compose.foundation.layout.Row"),
    (r"\bBox\(", "androidx.compose.foundation.layout.Box"),
    (r"\bBoxWithConstraints\(", "androidx.compose.foundation.layout.BoxWithConstraints"),
    (r"\bSpacer\(", "androidx.compose.foundation.layout.Spacer"),
    (r"\.background\(", "androidx.compose.foundation.background"),
    (r"\.clickable\s*[({]", "androidx.compose.foundation.clickable"),
    (r"\.combinedClickable\(", "androidx.compose.foundation.combinedClickable"),
    (r"\.clip\(", "androidx.compose.ui.draw.clip"),
    (r"\.alpha\(", "androidx.compose.ui.draw.alpha"),
    (r"\bAlignment\.", "androidx.compose.ui.Alignment"),
    (r"\bArrangement\.", "androidx.compose.foundation.layout.Arrangement"),
    (r"\bCircleShape\b", "androidx.compose.foundation.shape.CircleShape"),
    (r"\bTextOverflow\.", "androidx.compose.ui.text.style.TextOverflow"),
    (r"\bTextAlign\.", "androidx.compose.ui.text.style.TextAlign"),
    (r"\bFontWeight\.", "androidx.compose.ui.text.font.FontWeight"),
    (r"\bFocusRequester\(\)", "androidx.compose.ui.focus.FocusRequester"),
    (r"\.focusRequester\(", "androidx.compose.ui.focus.focusRequester"),
    (r"\.focusRestorer\(", "androidx.compose.ui.focus.focusRestorer"),
    (r"\.focusGroup\(", "androidx.compose.ui.focus.focusGroup"),
    (r"\.onFocusChanged\s*[({]", "androidx.compose.ui.focus.onFocusChanged"),
    (r"\bLocalContext\.current", "androidx.compose.ui.platform.LocalContext"),
    (r"\bhiltViewModel\b", "androidx.hilt.navigation.compose.hiltViewModel"),
    (r"\bviewModelScope\b", "androidx.lifecycle.viewModelScope"),
    (r"\bstateIn\(", "kotlinx.coroutines.flow.stateIn"),
    (r"\bSharingStarted\.", "kotlinx.coroutines.flow.SharingStarted"),
    (r"\bcombine\(", "kotlinx.coroutines.flow.combine"),
    (r"\.launch\s*[({]", "kotlinx.coroutines.launch"),
    (r"\bdelay\(", "kotlinx.coroutines.delay"),
    (r"\bwithContext\(", "kotlinx.coroutines.withContext"),
    (r"\bDispatchers\.", "kotlinx.coroutines.Dispatchers"),
    (r"@Inject\b", "javax.inject.Inject"),
    (r"@Singleton\b", "javax.inject.Singleton"),
    (r"\bLazyColumn\(", "androidx.compose.foundation.lazy.LazyColumn"),
    (r"\bLazyVerticalGrid\(", "androidx.compose.foundation.lazy.grid.LazyVerticalGrid"),
    (r"\bGridCells\.", "androidx.compose.foundation.lazy.grid.GridCells"),
    (r"\bPaddingValues\(", "androidx.compose.foundation.layout.PaddingValues"),
    (r"\brememberScrollState\(", "androidx.compose.foundation.rememberScrollState"),
    (r"\.verticalScroll\(", "androidx.compose.foundation.verticalScroll"),
    (r"\.horizontalScroll\(", "androidx.compose.foundation.horizontalScroll"),
    (r"\bOutlinedTextField\(", "androidx.compose.material3.OutlinedTextField"),
    (r"\bRadioButton\(", "androidx.compose.material3.RadioButton"),
    (r"\bSwitch\(", "androidx.compose.material3.Switch"),
    (r"\bCircularProgressIndicator\(", "androidx.compose.material3.CircularProgressIndicator"),
    (r"\bLinearProgressIndicator\(", "androidx.compose.material3.LinearProgressIndicator"),
    (r"\bColor\(0x|\bColor\.(White|Black|Transparent)", "androidx.compose.ui.graphics.Color"),
    (r"@Immutable\b", "androidx.compose.runtime.Immutable"),
    (r"@Composable\b", "androidx.compose.runtime.Composable"),
    (r"\.rightStickScroll\(", "com.rommmobile.app.core.input.rightStickScroll"),
    (r"\.gamepadFocusRing\(", "com.rommmobile.app.core.design.gamepadFocusRing"),
    (r"\.gamepadFocus\(", "com.rommmobile.app.core.design.gamepadFocus"),
    (r"\bRommTheme\.", "com.rommmobile.app.core.design.RommTheme"),
    (r"\bPillShape\b", "com.rommmobile.app.core.design.PillShape"),
    (r"\bFormat\.", "com.rommmobile.app.core.util.Format"),
    (r"\bKeyboardOptions\(", "androidx.compose.foundation.text.KeyboardOptions"),
    (r"\bImeAction\.", "androidx.compose.ui.text.input.ImeAction"),
    (r"\bKeyboardType\.", "androidx.compose.ui.text.input.KeyboardType"),
    (r"\bAnimatedVisibility\(", "androidx.compose.animation.AnimatedVisibility"),
    (r"\brememberSaveable\s*[({<]", "androidx.compose.runtime.saveable.rememberSaveable"),
    (r"\bderivedStateOf\s*\{", "androidx.compose.runtime.derivedStateOf"),
    (r"\bsnapshotFlow\s*\{", "androidx.compose.runtime.snapshotFlow"),
    (r"\brememberUpdatedState\(", "androidx.compose.runtime.rememberUpdatedState"),
    (r"\bCompositionLocalProvider\(", "androidx.compose.runtime.CompositionLocalProvider"),
    (r"\bImageVector\b", "androidx.compose.ui.graphics.vector.ImageVector"),
]

LAYOUT_MODS = ["fillMaxSize", "fillMaxWidth", "fillMaxHeight", "padding", "height", "width", "size", "aspectRatio", "widthIn", "heightIn", "fillMaxWidth"]


def covered(imp, imports):
    if imp in imports:
        return True
    pkg = imp.rsplit(".", 1)[0]
    return (pkg + ".*") in imports


def main():
    issues = []
    for f in glob.glob(os.path.join(ROOT, "**", "*.kt"), recursive=True):
        s = open(f, encoding="utf-8").read()
        imports = set(re.findall(r"^import\s+([\w.]+)", s, re.M))
        body = re.sub(r"^import.*$", "", s, flags=re.M)
        body = re.sub(r"^package.*$", "", body, flags=re.M)
        rel = os.path.relpath(f, ROOT)

        def need(sym, imp):
            if not covered(imp, imports):
                issues.append((rel, sym, imp))

        for pat, imp in SIMPLE:
            if re.search(pat, body):
                # a locally declared symbol with the same simple name shadows the need
                simple = imp.rsplit(".", 1)[1]
                if re.search(r"\b(fun|val|class|object)\s+" + re.escape(simple) + r"\b", body):
                    continue
                need(simple, imp)
        for name in set(re.findall(r"Icons\.Rounded\.(\w+)", body)):
            need("Icons.Rounded." + name, "androidx.compose.material.icons.rounded." + name)
        for name in set(re.findall(r"Icons\.AutoMirrored\.Rounded\.(\w+)", body)):
            need("Icons.AutoMirrored.Rounded." + name, "androidx.compose.material.icons.automirrored.rounded." + name)
        if re.search(r"\bIcons\.", body):
            need("Icons", "androidx.compose.material.icons.Icons")
        if re.search(r"\bby\s+(remember|rememberSaveable|[\w.]+\.collectAsStateWithLifecycle|rememberHasGamepad|animateFloatAsState|[\w.]+\.animateFloat|derivedStateOf|rememberUpdatedState)", body):
            need("getValue", "androidx.compose.runtime.getValue")
        if re.search(r"\bvar\s+\w+\s+by\s+(remember|rememberSaveable)", body):
            need("setValue", "androidx.compose.runtime.setValue")
        for mod in LAYOUT_MODS:
            if re.search(r"\." + mod + r"\(", body):
                need(mod, "androidx.compose.foundation.layout." + mod)
        if re.search(r"\bText\(", body) and "@Composable" in body:
            need("Text", "androidx.compose.material3.Text")
    for rel, sym, imp in issues:
        print(f"{rel}: uses {sym} but no import {imp}")
    print("issues:", len(issues))
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
