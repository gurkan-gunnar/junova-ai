# Junova AI 3B på Azure, hypotetisk drift

Det här paketet kör samma `Qwen2.5-3B-Instruct Q4_K_M` som Android-appen i en egen `llama.cpp`-container. Det använder ingen extern AI-API. Azure står bara för den dator som kör modellen.

## Arkitektur

- Azure Container Registry bygger och lagrar den privata containerbilden.
- Azure Container Apps kör `llama-server` på en T4 GPU i `swedencentral`.
- Modellen bakas in i containerbilden och SHA-256-verifieras under bygget.
- HTTPS avslutas av Container Apps.
- `LLAMA_API_KEY` ska lagras som en Container Apps-hemlighet, aldrig i appkoden.
- Android-appen fortsätter använda telefonens lokala modell tills ett serverläge uttryckligen aktiveras.

## Lokal kontroll

Kör från den här mappen med en egen slumpmässig nyckel:

```powershell
$env:JUNOVA_API_KEY = [guid]::NewGuid().ToString("N")
docker compose up --build
```

Kontrollera sedan hälsan på `http://localhost:8080/health`. Ett chatsvar kan testas mot `/v1/chat/completions` med `Authorization: Bearer <nyckeln>` och innehållet i `sample-request.json`.

## Hypotetiska Azure-steg

1. Skapa en resursgrupp och ett privat Azure Container Registry i `swedencentral`.
2. Bygg `Dockerfile` med ACR Tasks och publicera bilden som `junova/3b:3.30`.
3. Skapa en Container Apps-miljö med workload profiles och lägg till profilen `Consumption-GPU-NC8as-T4`.
4. Skapa en extern Container App på port `8080`, välj T4-profilen och sätt `LLAMA_ARG_N_GPU_LAYERS=99`.
5. Lägg en lång slumpmässig nyckel i en Container Apps-hemlighet och koppla den till miljövariabeln `LLAMA_API_KEY`.
6. Sätt minst en replica för snabb start eller noll för lägre kostnad och längre kallstart.
7. Tillåt bara HTTPS, begränsa CORS till Junova-klienten och lägg rate limiting framför tjänsten.

Exempel på ACR-bygge efter att resurserna har skapats:

```powershell
az acr build --registry <registry-namn> --image junova/3b:3.30 --file Dockerfile .
```

## Säkerhetskrav före riktig drift

- Lägg aldrig Azure- eller servernycklar direkt i APK-filen.
- Använd användarinloggning eller en liten autentiserad gateway för en publik mobilapp; en gemensam APK-nyckel går att extrahera.
- Stäng av promptloggning och skicka inte privata chattar till Application Insights.
- Lägg tidsgräns, maximal promptstorlek och rate limiting på varje begäran.
- Rotera nycklar och håll containerbilden samt `llama.cpp` uppdaterade.
- Kontrollera modellens licensvillkor innan publik eller kommersiell drift.

Paketet är avsiktligt inte distribuerat. En riktig distribution kan kosta pengar och kräver GPU-kvot i Azure-prenumerationen.
