import 'package:test/test.dart';

void main() {
  test(
    'nested loops accumulate in order and set/map insertion retains Dart behavior',
    () {
      for (var count = 0; count <= 2; count++) {
        final trace = <String>[];
        String element(int outer, int inner) {
          final value = '$outer:$inner';
          trace.add(value);
          return value;
        }

        final list = [
          for (var i = 0; i < count; i++)
            for (var j = 0; j < 2; j++) element(i, j),
        ];
        expect(list, switch (count) {
          0 => [],
          1 => ['0:0', '0:1'],
          _ => ['0:0', '0:1', '1:0', '1:1'],
        });
        expect(trace, list);
      }
      var calls = 0;
      int repeated() {
        calls++;
        return 1;
      }

      expect({for (var i = 0; i < 2; i++) repeated()}, {1});
      expect(calls, 2);
      expect({for (var i = 0; i < 2; i++) 'key': i}, {'key': 1});
    },
  );
  test(
    'null-aware entries evaluate a key once and skip its value for null',
    () {
      for (final key in <String?>[null, 'key']) {
        for (final value in <String?>[null, 'value']) {
          final trace = <String>[];
          String? read(String name, String? value) {
            trace.add(name);
            return value;
          }

          final map = {?read('key', key): ?read('value', value)};
          expect(trace, key == null ? ['key'] : ['key', 'value']);
          expect(map, key == null || value == null ? {} : {key: value});
          trace.clear();
          final list = [
            for (final item in [key, value]) ?read('item', item),
          ];
          expect(trace, ['item', 'item']);
          expect(list, [if (key != null) key, if (value != null) value]);
        }
      }
    },
  );
}
