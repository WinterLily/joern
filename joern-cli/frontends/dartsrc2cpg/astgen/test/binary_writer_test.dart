import 'dart:typed_data';

import 'package:analyzer/src/binary/binary_reader.dart';
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

  test(
    'pinned uint32 writer preserves byte order across the buffer boundary',
    () {
      for (final (value, bytes) in [
        (0, [0, 0, 0, 0]),
        (1, [0, 0, 0, 1]),
        (0x12345678, [0x12, 0x34, 0x56, 0x78]),
        (0xFFFFFFFF, [255, 255, 255, 255]),
      ]) {
        for (final padding in [0, 131071]) {
          final writer = BinaryWriter();
          for (var i = 0; i < padding; i++) {
            writer.writeByte(7);
          }
          writer.writeUint32(value);
          expect(writer.offset, padding + 4);
          final encoded = writer.takeBytes();
          expect(encoded.sublist(0, padding), List.filled(padding, 7));
          expect(encoded.sublist(padding), bytes);
          final reader = BinaryReader(encoded)..offset = padding;
          expect(reader.readUint32(), value);
          expect(reader.offset, padding + 4);
        }
      }
    },
  );

  test('pinned reader forks share bytes with independent offset state', () {
    final bytes = Uint8List.fromList([10, 20, 30, 40]);
    final original = BinaryReader(bytes)..offset = 1;
    final first = original.fork(0);
    final second = original.fork(2);
    expect(first.bytes, same(bytes));
    expect(second.bytes, same(bytes));
    expect(first.readByte(), 10);
    expect(second.readByte(), 30);
    expect(original.offset, 1);
    expect(first.offset, 1);
    expect(second.offset, 3);
    bytes[1] = 99;
    expect(first.readByte(), 99);
    expect(original.readByte(), 99);
    expect(second.readByte(), 40);
  });
}
