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
    public void exactPreferredMouseWinsOverOtherReadableMouse() throws Exception {
        String devices = snapshot("event14")
                + "\nI: Bus=0003 Vendor=0001 Product=0002 Version=0001\n"
                + "N: Name=\"Other USB Mouse\"\n"
                + "H: Handlers=mouse0 event5\n\n";
        InputDeviceDiscovery.Result result = discover(devices);
        assertEquals(NAME, result.name);
        assertEquals("/dev/input/event14", result.path);
    }

    private static InputDeviceDiscovery.Result discover(String snapshot) throws Exception {
        return InputDeviceDiscovery.discover(new StringReader(snapshot), NAME, path -> true);
    }

    private static String snapshot(String eventNode) {
        return "I: Bus=0005 Vendor=12d1 Product=0001 Version=0001\n"
                + "N: Name=\"" + NAME + "\"\n"
                + "H: Handlers=mouse2 " + eventNode + "\n\n";
    }
}
