# Junova AI

Junova AI ar en svensk-engelsk Android-assistent som kor lokalt pa telefonen utan extern AI-API. Appen kombinerar en egen kunskapsmotor med Gemma 4 E2B Instruct i 5B-klassen, lokalt samtalsminne, direkt webbsokning, OCR, bildanalys och lokalt bildskapande.

## Funktioner

- Tre motorlagen: kombinerad, Gemma 4 5B lokal och egen AI.
- Likvardiga svenska och engelska svar med automatiskt sprakval eller ett valt sprak i installningarna.
- Svarslagen Smart, Djup, Snabb, Kreativ, Bild, Kod och Research.
- Krypterade chattar som stannar pa enheten samt fastning och borttagning av chattar.
- Gemma 4 E2B Instruct Q4_0, cirka 4,63 miljarder parametrar (5B-klassen), via `llama.cpp` pa Android.
- Qwen2.5 3B kan ligga kvar som lokal reservmodell om 5B-filen saknas.
- Lokal kunskapsbas med amnesmatchning, raknemotor och langtidsminne.
- Kortare lokal generering, hard tidsgrans och faktabaserat reservsvar om modellen blir langsam.
- Relevanskontroll som stoppar fakta och modellsvar som byter till fel amne.
- Aspektkontroll som skiljer pa till exempel badkvalitet, agare, lage, pris och oppettider.
- Snabbare adaptiv lokal generering, relevantare faktameningar och battre tolkning av stavfel och foljdfragor.
- Webbsokning med kallankar nar aktuell eller saknad fakta behovs.
- OCR, motivanalys, farganalys och jamforelse av tva bilder.
- Lokal bildgenerator med stil- och formatval samt inspirationsbild.
- Diktering och notis nar ett svar blir klart i bakgrunden.

## Hamta projektet

Kloning till D-disken rekommenderas eftersom appens lokala byggmiljo och stora modeller ligger dar.

```powershell
git clone --recurse-submodules <repository-url> D:\Codex\AI-assistent
Set-Location D:\Codex\AI-assistent
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\apply-llama-android-patch.ps1
```

`llama.cpp` ligger som submodule. Den lilla Android-anpassningen sparas som en patch i repot sa att tredjepartskoden fortfarande gar att uppdatera kontrollerat.

## Lokal modell

Modellfilen ar cirka 3,35 GB och lagras darfor inte i GitHub. Hamta den fran Googles officiella Hugging Face-repository och verifiera SHA-256 automatiskt:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\download-model.ps1
```

For att lagga modellen i appens privata lagring anvands appens modellinstallation eller ADB-flodet for en testtelefon. Chattar, modellfiler, APK-filer, lokala SDK:er och testbilder ignoreras av Git.

## Bygg Android-appen

Krav: JDK 17, Android SDK 36 och Android build tools. Skapa `android-app\local.properties` med lokal `sdk.dir`, och kor sedan:

```powershell
Set-Location D:\Codex\AI-assistent\android-app
.\gradlew.bat :app:assembleDebug
```

Debug-signering kan anges lokalt med miljo- eller Gradle-egenskaper och ska inte laggas i repot. Azure-exemplet under `server\azure` ar en hypotetisk egen server for samma lokala modell; det anropar ingen extern AI-tjanst.

## Integritet

Junova sparar chattar krypterat i appens privata lagring och exkluderar dem fran Android-backup. Webbsokning skickar sokfrasen till de webbplatser som maste kontaktas, sa kansliga uppgifter ska inte anvandas som sokfraser.
