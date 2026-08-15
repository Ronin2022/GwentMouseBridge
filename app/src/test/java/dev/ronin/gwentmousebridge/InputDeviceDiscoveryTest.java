package dev.ronin.gwentmousebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.StringReader;

public class InputDeviceDiscoveryTest {
    private static final String NAME = "HUAWEI Mouse CD26 SE Mouse";

    @Test
    public void freshDiscoveryFollowsRenumberedEventNode() throws Exception {
        InputDeviceDiscovery.Result first = discover(snapshot("event14"));
        InputDeviceDiscovery.Result reconnected = discover(snapshot("event17"));

        assertEquals(NAME, first.name);
        assertEquals("/dev/input/event14", first.path);
        assertEquals("/dev/input/event17", reconnected.path);
    }

    @Test
    public void exactPreferredMouseWinsOverOtherMouse() throws Exception {
        String devices = snapshot("event14")
                + "\nI: Bus=0003 Vendor=0001 Product=0002 Version=0001\n"
                + "N: Name=\"Other USB Mouse\"\n"
                + "H: Handlers=mouse0 event5\n\n";
        InputDeviceDiscovery.Result result = discover(devices);
        assertEquals(NAME, result.name);
        assertEquals("/dev/input/event14", result.path);
    }

    @Test
    public void indentedProcLinesAreAcceptedWithoutJavaReadabilityPreflight() throws Exception {
        String devices = "  N: Name=\"" + NAME + "\"\n"
                + "  H: Handlers=event14 mouse2\n\n";
        InputDeviceDiscovery.Result result = discover(devices);
        assertEquals(NAME, result.name);
        assertEquals("/dev/input/event14", result.path);
    }

    @Test
    public void geteventInventoryFindsPreferredHuaweiMouse() throws Exception {
        String inventory = "add device 1: /dev/input/event5\n"
                + "  name:     \"Other USB Mouse\"\n"
                + "  events:\n"
                + "    REL (0002): REL_X REL_Y\n"
                + "add device 2: /dev/input/event14\n"
                + "  name:     \"" + NAME + "\"\n"
                + "  events:\n"
                + "    KEY (0001): BTN_MOUSE\n"
                + "    REL (0002): REL_X REL_Y\n";

        InputDeviceDiscovery.Result result = InputDeviceDiscovery.discoverGeteventInventory(
                new StringReader(inventory),
                NAME);
        assertEquals(NAME, result.name);
        assertEquals("/dev/input/event14", result.path);
    }

    @Test
    public void geteventInventoryFollowsRenumberedPreferredMouse() throws Exception {
        String inventory = "add device 7: /dev/input/event17\n"
                + "  name: \"" + NAME + "\"\n";
        InputDeviceDiscovery.Result result = InputDeviceDiscovery.discoverGeteventInventory(
                new StringReader(inventory),
                NAME);
        assertEquals("/dev/input/event17", result.path);
    }

    private static InputDeviceDiscovery.Result discover(String snapshot) throws Exception {
        return InputDeviceDiscovery.discover(new StringReader(snapshot), NAME);
    }

    private static String snapshot(String eventNode) {
        return "I: Bus=0005 Vendor=12d1 Product=0001 Version=0001\n"
                + "N: Name=\"" + NAME + "\"\n"
                + "H: Handlers=mouse2 " + eventNode + "\n\n";
    }
}
