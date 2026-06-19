# Contract: eGK VSDM Files (DF.HCA) + Access Rules

**Constants**: extend `de.servicehealtherx.apdu.model.GematikISO7816`; enum
`de.servicehealtherx.apdu.vsdm.EgkVsdmFile`. Source:
gemSpec_eGK_ObjSys_G2_1_V4.7.1 §5 (page-confirmed values below).

## Application

**DF.HCA** (Healthcareapplication) — AID `D2 76 00 00 01 02` (gematik well-known;
confirm byte-exact from the DF.HCA header table, §5.4, before coding the SELECT).
SELECT by AID (`INS A4`, P1 `04`).

## Elementary files

| EF | FID | SFID | Size (oct) | READ access | Returned as | Afo (Card-G2-A_) |
|---|---|---|---|---|---|---|
| `EF.StatusVD` | `D00C` | 12 | 25 | **ALWAYS** | parsed → `VsdStatus` | `_2401-01` |
| `EF.PD` | `D001` | 1 | 850 | **ALWAYS** | `PersoenlicheVersichertendaten` (raw) | `_2398-01` |
| `EF.VD` | `D002` | 2 | 1250 | **ALWAYS** | `AllgemeineVersicherungsdaten` (raw, VD offsets) | `_2403-01` |
| `EF.GVD` | `D003` | 3 | 600 | **requires C2C** (AUT_VSD) | `GeschuetzteVersichertendaten` (raw) | `_2396-01` |

### EF.GVD read access rule (eGK ObjSys p. 73 — verbatim intent)

```
READ BINARY(EF.GVD) =
    PWD(MRPIN.home)
 OR [PWD(PIN.CH) AND flagTI.29]
 OR flagTI.30
 OR { AUT_VSD }
```

The konnektor uses the **`{ AUT_VSD }`** branch — reached by the SMC-B/HBA C2C
(`contracts/c2c-authentication.md`). `AUT_VSD` is defined (eGK ObjSys p. 9) as

```
AUT_VSD = { SmMac(SK.VSD.AES128|AES256) OR SmMac(flagCMS.09) } AND SmCmdEnc AND SmRspEnc
```

→ the `READ BINARY` of EF.GVD **must be Secure-Messaging-wrapped** (CMAC + command &
response encryption) under the session keys from the C2C handshake. PD/VD/StatusVD are
plain (unwrapped) READ because their READ rule is ALWAYS.

## Reading mechanics

- `EgkFileReader` SELECTs DF.HCA once, then for each EF: `SELECT EF` (by FID, P2 `0C`)
  + looped `READ BINARY` (256-byte blocks, `0x6282` end-of-file), generalising
  `CardCertificateReader.readBinaryFull`, executed via `ApduExecutor`.
- **PD / VD / StatusVD**: plain READ BINARY. VD honours the VD start/end offsets so the
  transitional GVD copy embedded in EF.VD is never read (FR-020, VSDM-A_2784).
- **GVD**: same loop but each command/response wrapped in the C2C SM context.
- PD / VD / GVD payloads are returned **byte-for-byte** (gzip-compressed on card) —
  not decompressed, not parsed (FR-005).

## EF.StatusVD → `VsdStatus` conversion (Tab_FM_VSDM_21, VSDM-A_2708 / A_3063)

Container layout per gemSpec_eGK_Fach_VSDM Tab_eGK_Fach_VSDM_04 (25 octets). **Status and
Timestamp are alphanumeric (ASCII); only the two Version fields are BCD:**

| Field | Offset | Len | Encoding |
|---|---|---|---|
| `Status` | 0 | 1 | ASCII |
| `Timestamp` | 1 | 14 | ASCII `YYYYMMDDHHMMSS` (UTC) |
| `Version_XML` | 15 | 5 | BCD |
| `Version_Speicherstruktur` | 20 | 5 | BCD |

- `Status` ASCII `'0'` (consistent) / `'1'` (inconsistent → abort 3001, FR-017).
- `Timestamp` ASCII `20120131084713` → `dateTime` (`2012-01-31T08:47:13Z`, UTC).
- `Version_XML` BCD (`00 70 03 00 01`) → `7.3.1`.
- `Version_Speicherstruktur` BCD (`00 30 00 00 04` → `3.0.4`, eGK G2/G2.1) not
  transferred; an unknown storage-structure version → abort (VSDM-A_2979).

## Audit log (after read)

`EF.Logging` append-record (TUC_KON_006 → TucKon006WriteAudit) records the access,
including a "read protected VSD" entry when GVD was read; actor = subject of the
SMC-B/HBA AUT certificate (TUC_KON_034). Write failure aborts the call and returns no
VSD (FR-023, VSDM-A_2654).
