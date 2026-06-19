package de.servicehealtherx.apdu.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.servicehealtherx.apdu.vsdm.EgkVsdmFile;
import de.servicehealtherx.apdu.vsdm.FakeCardReaderPort;

class EgkFileReaderTest {

    private final UUID ctid = UUID.randomUUID();

    @Test
    void reads_a_file_across_multiple_256_byte_blocks_until_a_short_block() throws Exception {
        byte[] full = new byte[256];
        byte[] tail = new byte[100];
        var port = new FakeCardReaderPort(ctid, List.of(
                FakeCardReaderPort.ok(),                    // SELECT DF.HCA
                FakeCardReaderPort.ok(),                    // SELECT EF
                FakeCardReaderPort.resp(full, 0x9000),      // READ block 1 (256 → continue)
                FakeCardReaderPort.resp(tail, 0x9000)));    // READ block 2 (100 < 256 → stop)

        EgkFileReader reader = new EgkFileReader();
        reader.selectHca(port, 1);
        byte[] data = reader.read(port, 1, EgkVsdmFile.EF_PD);

        assertEquals(356, data.length);
    }

    @Test
    void stops_on_end_of_file_status_word() throws Exception {
        byte[] block = new byte[256];
        var port = new FakeCardReaderPort(ctid, List.of(
                FakeCardReaderPort.ok(),                    // SELECT EF
                FakeCardReaderPort.resp(block, 0x6282)));   // READ → EOF after a full block
        byte[] data = new EgkFileReader().read(port, 1, EgkVsdmFile.EF_VD);
        assertEquals(256, data.length);
    }
}
