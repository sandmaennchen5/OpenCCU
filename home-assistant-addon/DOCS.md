# Home Assistant Add-on: OpenCCU

This add-on provides all features of a standard, e.g. RaspberryPi, Tinkerboard or
even virtual (e.g. OVA) based OpenCCU system to be used as a HomeMatic or
homematicIP IoT central which is fully compatible to a CCU3 device.

## Per-user Ingress login

Enable `remember_ingress_users` to retain the OpenCCU WebUI session separately
for each Home Assistant user. The add-on stores only the temporary OpenCCU SID;
Home Assistant user IDs are hashed before they are used as file names. A normal
OpenCCU logout or an expired session removes the stored SID. After OpenCCU is
restarted, users must sign in again because the underlying WebUI sessions are no
longer valid.

## Installation

Follow these steps to install the add-on within your Home Assistant system:

1. Navigate in your Home Assistant frontend to **Settings** -> **Add-ons** -> **Add-on Store**.
2. Click on the vertical "⋮" dots on the top-right corner and select **Repositories**.
3. Enter the URL <https://github.com/OpenCCU/OpenCCU> as a new add-on repository and click **Add** and **Close**.
4. Find the "OpenCCU" add-on at the bottom of the add-on list and select it..
5. Click on the `INSTALL` button.
6. Install the add-on to the sidebar by clicking on `Show in sidebar`.
7. Start add-on by clicking on `START`
8. Click on the `OpenCCU` sidebar link to switch into the OpenCCU WebUI.

More detailed documentation on the installation and how to communicate with your HomeMatic/homematicIP devices
can be found in the official [OpenCCU documentation](https://github.com/OpenCCU/OpenCCU/wiki/Installation-HomeAssistant)

## How to use

After installation you can configure the add-on to add a "OpenCCU" link
to the sidebar of your Home Assistant user interface. After having done so, this link can
be used to access the standard OpenCCU WebUI to configure/setup your
HomeMatic/homematicIP devices in the usual CCU like way.

## HomeMatic/homematicIP device integration

After the OpenCCU add-on has been correctly installed and your OpenCCU WebUI shows existing HomeMatic/homematicIP devices, you still have to install and setup the general "HomeMatic integration". Only this step will bring up/integrate your HomeMatic/homematicIP devices - which the OpenCCU add-on shows - in the Home Assistant user interface, thus making them usable within the context of Home Assistant.

For this to happen, please follow the official OpenCCU documentation on the [HomeAssistant integration](https://github.com/OpenCCU/OpenCCU/wiki/HomeAssistant-Integration).
