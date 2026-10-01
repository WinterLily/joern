import 'package:analyzer/src/binary/binary_writer.dart';
import 'package:test/test.dart';

void main() {
  test(
    'pinned binary writer preserves bytes across a flush and separate writers',
    () {
      for (final byte in [0, 1, 255]) {
        final first = BinaryWriter();
        final second = BinaryWriter();
        for (var i = 0; i < 131073; i++) {
          first.writeByte(byte);
        }
        second.writeByte(255 - byte);
        expect(first.offset, 131073);
        expect(second.offset, 1);
        expect(first.takeBytes(), List.filled(131073, byte));
        expect(second.takeBytes(), [255 - byte]);
      }
    },
  );

  test('pinned writeList visits unchanged elements in order exactly once', () {
    for (final values in <List<int>>[
      [],
      [0],
      [255, 1, 0],
    ]) {
      final items = List<int>.unmodifiable(values);
      final first = BinaryWriter();
      final second = BinaryWriter();
      final visits = <int>[];
      first.writeList<int>(items, (item) {
        visits.add(item);
        first.writeByte(item);
        second.writeByte(255 - item);
      });
      expect(visits, values);
      expect(items, values);
      expect(first.takeBytes(), [values.length, ...values]);
      expect(second.takeBytes(), values.map((value) => 255 - value));
    }
  });
}
