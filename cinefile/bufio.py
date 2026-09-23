"""Minimal Minecraft FriendlyByteBuf reader."""

from __future__ import annotations

import struct
import uuid
from typing import Tuple


class ByteReader:
    __slots__ = ("data", "i", "n")

    def __init__(self, data: bytes, offset: int = 0):
        self.data = data
        self.i = offset
        self.n = len(data)

    def remaining(self) -> int:
        return self.n - self.i

    def require(self, nbytes: int) -> None:
        if self.i + nbytes > self.n:
            raise EOFError(f"need {nbytes} bytes at {self.i}, have {self.remaining()}")

    def read_bytes(self, nbytes: int) -> bytes:
        self.require(nbytes)
        out = self.data[self.i : self.i + nbytes]
        self.i += nbytes
        return out

    def skip(self, nbytes: int) -> None:
        self.require(nbytes)
        self.i += nbytes

    def read_u8(self) -> int:
        return self.read_bytes(1)[0]

    def read_bool(self) -> bool:
        return self.read_u8() != 0

    def read_int(self) -> int:
        return struct.unpack(">i", self.read_bytes(4))[0]

    def read_float(self) -> float:
        return struct.unpack(">f", self.read_bytes(4))[0]

    def read_double(self) -> float:
        return struct.unpack(">d", self.read_bytes(8))[0]

    def read_uuid(self) -> uuid.UUID:
        return uuid.UUID(bytes=self.read_bytes(16))

    def read_varint(self) -> int:
        num = 0
        shift = 0
        while True:
            b = self.read_u8()
            num |= (b & 0x7F) << shift
            if not (b & 0x80):
                return num
            shift += 7
            if shift > 35:
                raise ValueError("VarInt too long")

    def read_utf(self, max_len: int = 32767) -> str:
        length = self.read_varint()
        if length > max_len * 3:
            raise ValueError(f"UTF length {length} too large")
        return self.read_bytes(length).decode("utf-8")

    def read_identifier(self) -> str:
        return self.read_utf(32767)

    def read_resource_key(self) -> Tuple[str, str]:
        """Read ResourceKey as registry id + location (Minecraft FriendlyByteBuf)."""
        # ResourceKey is written as Identifier of the location only when registry is known
        # via writeResourceKey(Registries.DIMENSION) -> writes ResourceLocation of the key.
        # In practice Flashback writes: dimension Identifier string like "minecraft:overworld"
        loc = self.read_identifier()
        if ":" in loc:
            ns, path = loc.split(":", 1)
            return ns, path
        return "minecraft", loc
