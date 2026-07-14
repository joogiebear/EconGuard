# EconGuard

**Shared economy audit ledger and anti-abuse / RMT-detection core** for Paper.

EconGuard doesn't move money and has no player-facing features. It's the piece the *other* economy
plugins report to: one ledger, one place to look, one audit trail for a ban or rollback.

Part of a suite with [RoyalBank](https://github.com/joogiebear/RoyalBank),
[RoyalAuctions](https://github.com/joogiebear/RoyalAuctions) and
[RoyalBazaar](https://github.com/joogiebear/RoyalBazaar) — but the API is public and generic, so any
plugin can feed it.

---

## Contents

- [Why a separate plugin](#why-a-separate-plugin)
- [What it can and cannot do](#what-it-can-and-cannot-do)
- [Install](#install)
- [Commands & permissions](#commands--permissions)
- [Configuration](#configuration)
- [The detection signals](#the-detection-signals)
- [Tuning](#tuning)
- [Developer API](#developer-api)
- [Storage](#storage)
- [Building](#building)

---

## Why a separate plugin

Every economy plugin ends up growing its own half-blind anti-abuse layer. The bank sees deposits, the
auction house sees sales, the shop sees purchases — and **none of them see each other**, so the one
pattern that actually matters is invisible to all of them:

> a three-day-old account receives 500M through an auction "sale" of a dirt block, immediately banks
> it, and withdraws it over the next week.

The bank sees a big deposit. The auction house sees a weird sale. Neither sees *RMT*. EconGuard sees
the whole chain because everything reports into one ledger, and correlation runs **across** sources.

That's also why it's a standalone plugin rather than a library: with a library each plugin gets its
own copy of the state and they still can't see each other.

## What it can and cannot do

**Can:** surface suspicious patterns, keep a unified per-player money history, correlate counterparties
across bank/auction/shop/pay, and give you an evidence trail for enforcement.

**Cannot:** prevent real-money trading. RMT is an out-of-game agreement; the in-game half looks exactly
like a legitimate gift. Nothing running on your server can distinguish them with certainty — which is
why EconGuard **flags for staff review** and never auto-punishes. A false ban costs you far more than
a missed flag.

To actually *cap* the damage, pair it with friction in the economy itself: a maximum currency cap, and
limits/cooldowns/tax on player-to-player transfers.

---

## Install

1. Drop `EconGuard.jar` into `plugins/`.
2. Start the server.
3. Any suite plugin present will start reporting automatically — no wiring needed.

EconGuard has no hard dependencies. If it isn't installed, the reporting plugins simply skip the call.

## Commands & permissions

Base command `/econguard`, aliases **`/eg`**, `/econ`.

| Command | |
|---|---|
| `/eg flags` | List accounts flagged for review |
| `/eg flags clear [player]` | Clear all flags, or one player's |
| `/eg history <player> [limit]` | That player's unified money history across every source |
| `/eg stats` | Ledger size, flag count, detection status |
| `/eg reload` | Reload `config.yml` |

| Node | Default | |
|---|---|---|
| `econguard.admin` | `op` | The commands above |
| `econguard.alerts` | `op` | Receive alerts and flag notices in chat |

---

## Configuration

```yaml
currency-symbol: "$"        # formatting for alerts only
notify-staff: true          # ping online econguard.alerts holders
discord-webhook: ""         # optional; blank = off

database:
  file: "econguard.db"
  max-rows-per-player: 500  # pruned on startup. 0 = unlimited

# "Young account" definition — see below. Either condition qualifies.
young-account:
  max-age-days: 3
  max-playtime-hours: 10

detection:
  large-transaction: 100000000.0
  young-incoming-transfer: 50000000.0
  velocity:
    enabled: true
    window-minutes: 30
    threshold: 250000000.0
  counterparty:
    enabled: true
    window-minutes: 60
    threshold: 500000000.0
```

## The detection signals

**The `young-account` gate is the core idea.** Two of the four signals only fire on *young* accounts,
and that's deliberate: a veteran who legitimately sells a lucky drop for 200M is not suspicious, and
flagging them is how an anti-abuse system trains staff to ignore it. What *is* suspicious is **wealth
arriving with no time invested** — that's the actual RMT fingerprint, and the one thing a buyer can't
fake without playing the game.

An account is "young" if it joined within `max-age-days` **or** has under `max-playtime-hours`
playtime. Either qualifies — an alt farmed to 4 days old but with 20 minutes played is still young.

| Signal | Fires when | Gate |
|---|---|---|
| **Large transaction** | any single movement ≥ `large-transaction` | all accounts (informational) |
| **Young incoming transfer** | a young account *receives* ≥ `young-incoming-transfer` in one go | young only |
| **Velocity** | a young account gains ≥ `threshold` incoming within `window-minutes` | young only |
| **Counterparty correlation** | the same payer funds the same receiver ≥ `threshold` within `window-minutes` — **both** are flagged | **all ages** |

Counterparty correlation deliberately ignores account age: it catches the *ring*, and the seller in an
RMT ring is usually an established account. That's what a per-plugin anti-abuse layer structurally
cannot see, because the transfers are spread across the bank, the auction house and `/pay`.

Alerts go to the console, to online `econguard.alerts` holders, and to the Discord webhook. Staff with
the permission are told the pending flag count when they join.

## Tuning

The defaults are deliberately **high** — they're set for a server with a large currency supply, and on
a small economy they will simply never fire.

Calibrate against your own numbers, not the defaults: look at what an established player's balance
actually is, and set `large-transaction` somewhere around what an unusual-but-legitimate single trade
looks like. Then run for a week and read `/eg flags`. A system that flags twenty accounts a day gets
ignored within a week, and an ignored flag is worth exactly as much as no flag.

---

## Developer API

Feed EconGuard from any plugin. Nothing here compiles against a Royal* plugin — the API is generic.

**Reporting an event:**

```java
import com.mystipixel.econguard.api.*;

EconGuard.get().ifPresent(api -> api.record(
    MoneyEvent.builder(player.getUniqueId(), player.getName())
              .source(Sources.BANK)      // bank pay auction shop admin interest other
              .action("deposit")         // free-form; shown in /eg history
              .amount(amount)
              .incoming(false)           // is the player RECEIVING money?
              .counterparty(otherUuid)   // optional — required for correlation detection
              .item("ecoitems:brazen_sword")   // optional
              .balanceAfter(newBalance)  // optional
              .note("via /ah buy")       // optional
              .build()));
```

`EconGuard.get()` returns `Optional.empty()` when the plugin isn't installed, so the `ifPresent` call
above is the whole guard — **no soft-depend check, no `isPluginEnabled`, no reflection.** Your plugin
works with or without it, and the integration is one statement.

**The interface:**

```java
public interface EconGuardAPI {
    void record(MoneyEvent event);
    List<Flag> getFlags();
    boolean clearFlag(UUID player);
    void clearFlags();
    List<MoneyEvent> getHistory(UUID player, int limit);
}

public record Flag(UUID player, String playerName, String type, String reason, long timestamp) {}
```

**Two things to get right:**

- **`incoming` is from the reporting player's perspective.** A bank *deposit* is money leaving their
  purse → `incoming(false)`. An auction *sale* pays the seller → `incoming(true)` on the seller's
  event. Get this backwards and velocity detection points at the wrong side of every trade.
- **Set `counterparty` whenever there is one.** Correlation detection is the signal that catches RMT
  rings, and it's the one that goes quietly dead if the field is left null. A shop purchase has no
  counterparty (the server is); a `/pay` or an auction sale absolutely does.

Add it as a `provided` dependency and `softdepend: [EconGuard]` in your `plugin.yml`.

## Storage

SQLite (`econguard.db`). Two tables: the money ledger and the flag list. Writes are async; the ledger
is pruned to `max-rows-per-player` on startup so it can't grow unbounded on a long-lived server.

## Building

```bash
mvn -DskipTests package     # -> target/EconGuard.jar
```

Java 21, Maven. Versioning is `year.week.revision` (e.g. `2026.28.0`), matching the eco suite.
