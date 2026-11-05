package cn.bg7qvu.htisw.taxilink;
import org.junit.jupiter.api.Test;
import static cn.bg7qvu.htisw.taxilink.Json.*;
import static org.junit.jupiter.api.Assertions.*;
class ProtocolTest {
    @Test void preservesLongIdsAndRejectsLossyNumbers() {
        assertEquals(Long.MAX_VALUE, id(object("{\"id\":\"9223372036854775807\"}"), "id"));
        assertEquals(Long.MIN_VALUE, id(object("{\"id\":\"-9223372036854775808\"}"), "id"));
        for (String json : new String[]{"{\"id\":9007199254740993}", "{\"id\":\"9223372036854775808\"}", "{\"id\":\"0\"}", "{\"id\":\"1.5\"}"})
            assertThrows(Fault.class, () -> id(object(json), "id"));
    }
    @Test void rejectsOutOfRangeAndWrongTypes() {
        assertThrows(Fault.class, () -> number(object("{\"n\":\"30\"}"), "n", 1, 160));
        assertThrows(Fault.class, () -> number(object("{\"n\":1e309}"), "n", 1, 160));
        assertThrows(Fault.class, () -> number(object("{\"n\":0}"), "n", 1, 160));
    }
    @Test void heldDoorControlsAreIdempotentAndReleasable() {
        Object vehicle = new Object(); DoorControl.set(vehicle, true);
        assertTrue(DoorControl.blocks(vehicle, false)); assertFalse(DoorControl.blocks(vehicle, true));
        DoorControl.set(vehicle, false); assertTrue(DoorControl.blocks(vehicle, true));
        DoorControl.remove(vehicle); assertFalse(DoorControl.blocks(vehicle, true));
    }
}
