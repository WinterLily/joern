import 'package:test/test.dart';

import '../../src/test/resources/semantics/field_witness.dart' as fixture;

class RecordingWriter extends fixture.Writer {
  final values = <int>[];

  @override
  void count(int value) => values.add(value);
}

void main() {
  test('inherited writers visit independent fields in declared order', () {
    for (final input in [0, 1, 2]) {
      final writer = RecordingWriter();
      final record = fixture.Derived(
        fixture.Item(40, 41),
        [fixture.Item(input, 9)],
        [fixture.Item(20, 21)],
        [fixture.Item(30, 31)],
      );
      record.write(writer);
      expect(writer.values, [1, 30, 31, 1, input, 9, 1, 20, 21, 40, 41]);
      expect(record.first.single.value, input);
      expect(record.second.single.value, 20);
      expect(record.third.single.value, 30);
    }
  });
}
