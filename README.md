# AI Companion (Fabric mod for Minecraft Java 26.3)

A buddy that **joins your world as a real player**. It shows up in the tab list, walks, jumps, swims,
breaks and places blocks, crafts tools, fights mobs, and **talks with you in chat**.

## Controls

* **Down Arrow**: opens the companion menu (you can change the key in *Options > Controls > Key Binds > AI Companion*).
* **Chat**: just type normally. If you're the only real player, your companion listens to everything.
  With friends online, put its name in the message (for example `Buddy, follow me`).

### The menu

| Button | What it does |
|---|---|
| Join World | Your companion joins the world next to you (type a name in the box first, e.g. `Buddy`) |
| Follow Me / Stay Here | Follows you around, or waits where it is |
| Free Play | Plays on its own near you: chops trees, mines ores it can see, picks up items, explores |
| Guard Me | Fights every hostile mob near you |
| Chop Wood | Finds real trees (never your house!), chops them, replants saplings |
| Go Mining | Digs a staircase down and tunnels for ores. If it has no pickaxe it chops wood and crafts one first |
| Collect Items | Picks up everything lying on the ground nearby |
| Give Me Items | Walks over and tosses you its loot (keeps one of each tool) |
| Build: House / Tower / Wall / Bridge / Shelter | Builds right in front of you, block by block |
| Free Blocks: toggle | ON = builds without using up its own blocks. OFF = only uses blocks it has |
| Invincible: toggle | ON = can't get hurt. OFF = can die like a normal player (it respawns next to you) |
| Dance! | Self-explanatory |
| Leave World | It leaves (and keeps its stuff, so calling it back with the same name brings everything back) |

### Things you can say in chat

`hi` · `follow me` · `stay` · `guard me` · `chop some wood` · `go mining` · `find diamonds` · `mine iron`
· `build a house` · `build a tower` · `make a shelter` · `give me your wood` · `give me everything`
· `what do you have?` · `what are you doing?` · `dance` · `tell me a joke` · `help` · `bye`

### Extras

* It crafts its own planks, sticks, crafting table, wooden/stone/iron tools and torches.
* When you sleep, it "goes to bed" too, so the night still skips.
* It follows you through Nether/End portals.
* It saves with your world and comes back when you rejoin.
* You can also type commands: `/companion summon Buddy`, `/companion build house`, `/companion mine diamonds`...

### Optional: real AI conversations

Out of the box the companion uses its own built-in chat brain. To give it real AI conversations,
open `.minecraft/config/aicompanion.json` (it's created the first time you start the game) and paste
a Claude API key (from https://console.anthropic.com) into `"anthropicApiKey"`, then type
`/companion reload` in game. API usage is billed by Anthropic to whoever owns the key.

## Getting the .jar

**Option A: let GitHub build it (no software to install)**
1. Create a free account at https://github.com and make a new repository.
2. Upload everything in this folder (drag and drop works, including the hidden `.github` folder).
3. Open the **Actions** tab, then the latest *Build the mod* run, then download **ai-companion-mod** at the bottom.
   The `.jar` without `-sources` in its name is the mod.

**Option B: build it on your Windows PC**
1. Install Java 25 (free): https://adoptium.net/temurin/releases/?version=25
2. Double-click `BUILD-MOD-WINDOWS.bat`. The finished mod shows up in the `READY-TO-USE` folder.

On Mac or Linux, run `./gradlew build` in this folder; the mod lands in `build/libs/`.

## Installing

You need **Fabric Loader 0.19.5+** and **Fabric API for 26.3** (https://modrinth.com/mod/fabric-api).

* **Lunar Client**: pick version 26.3 with the Fabric option, open the *Mods* tab, and drag in
  `ai-companion-1.0.0.jar` and the Fabric API jar.
* **Normal Minecraft Launcher**: install Fabric for 26.3 (https://fabricmc.net/use/installer), then put both jars in
  `%appdata%\.minecraft\mods`.

Works in singleplayer and LAN worlds. On a multiplayer server, the server needs the mod installed too.
