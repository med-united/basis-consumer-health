# Contract: Card-to-Card Authentication (TUC_KON_005, ELC, VSDM)

**Class**: `de.servicehealtherx.apdu.tuc.TucKon005CardToCardAuth` (replaces the current
3-APDU stub) + helpers in `de.servicehealtherx.apdu.c2c`.
**Goal**: establish the **AUT_VSD Trusted Channel** between the SMC-B/HBA and the eGK
so the eGK's protected file `EF.GVD` can be read. Sources: gemSpec_COS_V3.14.0 §14.8.7
/ §14.9.9 / §15, gemSpec_eGK_ObjSys_G2_1_V4.7.1, gemSpec_SMC-B_ObjSys_G2_1_V5.2.0.

## What the cards do vs. what the konnektor does

| On-card (delegated — no host crypto) | Host / konnektor (this feature implements) |
|---|---|
| ECDSA verification of each CVC (`PSO VERIFY CERTIFICATE`) | Read the CVC files; **parse CVC TLV** (7F21/7F4E/5F37, CAR/CHR/CHAT/CED/CXD) to order the chain & pick key references |
| ECKA-DH shared-secret + session-key derivation (`GENERAL AUTHENTICATE`) | Sequence the MSE / GET CHALLENGE / GENERAL AUTHENTICATE APDUs in the right order/direction |
| Raising the AUT_VSD security state from the verified CHAT | **Secure-Messaging wrap/unwrap** of the subsequent `READ BINARY` of EF.GVD (AES-CMAC + AES-CBC over ISO 7816-4 SM DOs) |

> **Primary implementation risk** is on the host side: the SM channel handling for the
> GVD read and the CVC TLV parsing/chain ordering. Bouncy Castle (already adopted)
> supplies AES/CMAC and ASN.1/TLV; the ECDSA/ECKA primitives stay on-card.

## CVC chain (trust path)

`PuK.RCA.CS.E256` (root anchor, `PSO VERIFY CERTIFICATE → ALWAYS`)
→ `EF.C.CA.CS.E256` (FID `2F07`, SFID 7, READ ALWAYS)
→ leaf: eGK `EF.C.eGK.AUT_CVC.E256` / SMC-B `EF.C.SMC.AUTR_CVC.E256` (FID `2F06`, SFID 6, READ ALWAYS).
Curve **brainpoolP256r1**; the SMC-B/HBA institutional role lives in the `AUTR_CVC`
CHAT flagList and satisfies the eGK's `AUT_VSD` / `flagCMS.09` access rule.

## APDU sequence (ELC mutual auth with session keys — COS §15.4.4)

For each direction the konnektor, via `ApduExecutor`:

1. **Read partner CVCs** — plain `READ BINARY` of `EF.C.*.AUT_CVC.E256` (SFID 6) and
   `EF.C.CA.CS.E256` (SFID 7). (All READ = ALWAYS.)
2. **Import + verify each CVC** into the verifying card —
   `MSE SET` verification key (`INS 22`, P1 `81` P2 `B6`, data `83||keyRef`) then
   `PSO VERIFY CERTIFICATE` (`INS 2A`, P1 `00`, P2 `BE`, data = CVC body). Card checks
   signature, CAR↔CHR, CED/CXD, CHAT role bits (root anchor = CHAT bits b0 b1 = `11`).
3. **Select keys** — `MSE SET` for the auth (`INS 22`, P1 `81` P2 `A4`, data
   `83 0C||keyRef || 80 01||algId`). For the session-key variant the private ELC key
   reference is named inside `GENERAL AUTHENTICATE` itself.
4. **Authenticate / agree session keys** — two-step `GENERAL AUTHENTICATE` (`INS 86`):
   - Step 1 (`CLA 10`, chaining): data `7C-0E-(C3-0C-keyRef)` → selects the imported
     partner public key, card returns its ephemeral public point.
   - Step 2 (`CLA 00`): data `7C-Lc-(85-..-ephemeralPK_opponent)` → import the other
     side's ephemeral point.
   - Each card computes `K1=ECKA(PrK_static, PK_ephem_other)`,
     `K2=ECKA(SK_ephem, PuK_static_other)`, `K=K1||K2`, derives session keys, and sets
     its security state from the verified CHAT. `algId` asymmetry binds the halves:
     `elcSessionkey4TC` (Trusted Channel) ↔ `elcSessionkey4SM` (Secure Messaging).

A **one-sided** variant (COS §15.1.3 / §15.2: `GET CHALLENGE` + `EXTERNAL/INTERNAL
AUTHENTICATE` with `elcRoleCheck` / `elcRoleAuthentication`) is used in the
post-update case where only the SMC-B/HBA role needs proving (VSDM-A_2572) — not the
default for a cold local read.

## Key references / files

| Object | eGK | SMC-B |
|---|---|---|
| Leaf C2C CVC | `EF.C.eGK.AUT_CVC.E256` FID `2F06`/SFID 6, CHR `0009‖ICCSN` | `EF.C.SMC.AUTR_CVC.E256` FID `2F06`/SFID 6, CHR `0006‖ICCSN` |
| Private ELC key | `PrK.eGK.AUT_CVC.E256` keyId `09`, brainpoolP256r1 | `PrK.SMC.AUTR_CVC.E256` keyId `06` (needs PIN.SMC verified) |
| CA link CVC | `EF.C.CA.CS.E256` FID `2F07`/SFID 7 | `EF.C.CA.CS.E256` FID `2F07`/SFID 7 |
| Root anchor | `PuK.RCA.CS.E256` | `PuK.RCA.CS.E256` |

## Output / postcondition

On success the eGK is in the **AUT_VSD** security state, and the C2C helper returns an
**SM session context** (session keys / send-sequence counter) that `EgkFileReader`
uses to wrap the EF.GVD `READ BINARY`. If C2C fails to reach AUT_VSD, GVD is **not**
read and the operation still returns PD/VD/Status (FR-021). PIN preconditions:
SMC-B PIN.SMC / HBA PIN.CH must be verified first, else 3041 / 3042 (FR-012).

## Requirement IDs

COS: GENERAL AUTHENTICATE ELC A_16528–A_16531, A_16550; PSO Verify Certificate
A_16724–A_16735, A_16751–A_16757; MSE A_16924–A_16927 / A_16890–A_16894.
eGK ObjSys: EF.GVD `Card-G2-A_2396-01`, AUT_VSD rule (p. 9); CVC/keys
`_2363/_2364-01/_3208`, `_2377-01/_3211`, `_2359-01`, `_2380-01/_3243`.
SMC-B ObjSys: `_2163-01/_3389`, `_2180-01/_3355`, `_2160-03`, `_2192-01`.

## Open item (confirm during implementation)

Byte-exact **DF.HCA application AID** — expected gematik well-known `D2 76 00 00 01 02`
— to be confirmed from the DF.HCA header table (gemSpec_eGK_ObjSys §5.4) before coding
the SELECT. Everything else above is page-confirmed.
