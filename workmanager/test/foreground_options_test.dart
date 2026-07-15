import 'package:test/test.dart';
import 'package:workmanager/workmanager.dart';

void main() {
  test('foreground options map includes notification configuration', () {
    const options = WorkmanagerForegroundOptions(
      notificationId: 42,
      channelId: 'ota',
      channelName: 'Firmware updates',
      channelDescription: 'Shows firmware update progress',
      title: 'Updating earpiece',
      description: 'Transferring firmware',
      foregroundServiceType:
          ForegroundServiceType.dataSync |
          ForegroundServiceType.connectedDevice,
      progress: 25,
      smallIconResourceName: 'ic_notification',
      cancelLabel: 'Cancel',
    );

    expect(options.toMap(), {
      'notificationId': 42,
      'channelId': 'ota',
      'channelName': 'Firmware updates',
      'channelDescription': 'Shows firmware update progress',
      'title': 'Updating earpiece',
      'description': 'Transferring firmware',
      'foregroundServiceType': 17,
      'progress': 25,
      'smallIconResourceName': 'ic_notification',
      'cancelLabel': 'Cancel',
    });
  });
}
