# MyAgent v2: hands-free personal voice agent (Android, free)

"Hey Agent" bolle jege othe → beep → tumi command bolo → Gemini bujhe tool chalay
(call, SMS, WhatsApp, alarm, timer, open app) → voice e uttor dey → abar shunte thake.

## Hands-free on korar niyom (prothom bar)
App e 🎧 button chapo. Ek ek kore ei gulo chaibe (prottek bar allow kore ferot eshe abar 🎧 chapo):
1. Microphone permission
2. "Display over other apps" ON (na hole background theke call/WhatsApp/alarm khulbe na)
3. Battery: Unrestricted / Allow (na hole phone app ta mere dey)
Tarpor ~40MB wake-word model ekbar download hobe (WiFi use koro). Notification e "Say hey agent" dekhale ready.

Call/SMS er age agent voice e jiggesh korbe, tumi "yes" ba "no" bolo.
Wake phrase bodlate ⚙ e (English shobdo, jemon "hey jarvis"), tarpor hands-free off/on.


## Phone e kivabe build + install korbe (computer lagbe na)

### 1. GitHub account + private repo
- github.com e free account banao.
- New repository → naam `myagent` → **Private** → Create.

### 2. Token banao (push korar password)
GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) →
Generate new token → tick `repo` and `workflow` → copy kore rakho.

### 3. Termux diye upload (phone)
Termux F-Droid theke install koro (Play Store version purono). Tarpor:

```
pkg update -y && pkg install -y git unzip
termux-setup-storage
cd ~
unzip ~/storage/downloads/MyAgent.zip
cd MyAgent
git config --global user.name "TomarNaam"
git config --global user.email "tomar@email.com"
git init -b main
git add .
git commit -m "first version"
git remote add origin https://github.com/TOMAR_USERNAME/myagent.git
git push -u origin main
```
Username chaile GitHub username, password e **token** paste koro.

### 4. APK pao
- GitHub repo → **Actions** tab → "Build APK" cholche (~5 min).
- Sesh hole repo er **Releases → Latest APK → app-debug.apk** download koro.
- Install koro (Chrome/Files theke "Install unknown apps" allow koro).

### 5. App er setup
- App khulle permission allow koro (Contacts, Phone, SMS).
- ⚙ chepe: Gemini API key, tomar naam, model, voice language boshao. Save.
- 🎤 chepe bolo: "Ammu ke WhatsApp e bolo ajke aste late hobe".

## Naam/branding bodlano
- App er naam: `app/src/main/res/values/strings.xml`
- Icon er rong: `app/src/main/res/values/colors.xml`
- Agent er personality/niyom: `Agent.kt` er `systemPrompt()`

## Notun kaj (tool) add korte
`Tools.kt` e `declarations()` e ekta entry + `execute()` e ekta line + ekta function.

## Shotorkota
- API key code e nei, shudhu phone e save hoy. Repo private rakho.
- Call/SMS er age app Yes/No jiggesh kore.
- WhatsApp e message prefill hoy, **Send tumi nije chapbe** (WhatsApp er official API chhara auto-send fragile).
