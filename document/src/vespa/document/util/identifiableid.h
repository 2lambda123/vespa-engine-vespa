// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vespalib/objects/identifiable.h>

#define CID_Serializable           DOCUMENT_CID(1)
#define CID_Deserializable         DOCUMENT_CID(2)
#define CID_Document               DOCUMENT_CID(3)
#define CID_DocumentId             DOCUMENT_CID(4)
#define CID_DocumentUpdate         DOCUMENT_CID(6)
#define CID_Update                 DOCUMENT_CID(7)
#define CID_DocumentBase           DOCUMENT_CID(8)
#define CID_FieldValue             DOCUMENT_CID(9)
#define CID_ByteFieldValue         DOCUMENT_CID(10)
#define CID_IntFieldValue          DOCUMENT_CID(11)
#define CID_LongFieldValue         DOCUMENT_CID(12)
#define CID_FloatFieldValue        DOCUMENT_CID(13)
#define CID_DoubleFieldValue       DOCUMENT_CID(14)
#define CID_StringFieldValue       DOCUMENT_CID(15)
#define CID_RawFieldValue          DOCUMENT_CID(16)
#define CID_BoolFieldValue         DOCUMENT_CID(18)
#define CID_ArrayFieldValue        DOCUMENT_CID(19)
#define CID_WeightedSetFieldValue  DOCUMENT_CID(20)
#define CID_FieldMapValue          DOCUMENT_CID(21)
#define CID_ShortFieldValue        DOCUMENT_CID(22)
#define CID_ValueUpdate            DOCUMENT_CID(24)
#define CID_AddValueUpdate         DOCUMENT_CID(25)
#define CID_ArithmeticValueUpdate  DOCUMENT_CID(26)
#define CID_AssignValueUpdate      DOCUMENT_CID(27)
#define CID_ClearValueUpdate       DOCUMENT_CID(28)
#define CID_MapValueUpdate         DOCUMENT_CID(29)
#define CID_RemoveValueUpdate      DOCUMENT_CID(30)
#define CID_CollectionFieldValue   DOCUMENT_CID(31)
#define CID_StructuredFieldValue   DOCUMENT_CID(32)
#define CID_StructFieldValue       DOCUMENT_CID(33)
#define CID_LiteralFieldValueB     DOCUMENT_CID(34)
#define CID_NumericFieldValueBase  DOCUMENT_CID(35)
#define CID_MapFieldValue          DOCUMENT_CID(36)
#define CID_PredicateFieldValue    DOCUMENT_CID(37)
#define CID_TensorFieldValue       DOCUMENT_CID(38)
#define CID_ReferenceFieldValue    DOCUMENT_CID(39)

#define CID_document_FieldPathEntry DOCUMENT_CID(80)

#define CID_FieldPathUpdate        DOCUMENT_CID(85)
#define CID_AddFieldPathUpdate     DOCUMENT_CID(86)
#define CID_AssignFieldPathUpdate  DOCUMENT_CID(87)
#define CID_RemoveFieldPathUpdate  DOCUMENT_CID(88)

#define CID_TensorModifyUpdate     DOCUMENT_CID(100)
#define CID_TensorAddUpdate        DOCUMENT_CID(101)
#define CID_TensorRemoveUpdate     DOCUMENT_CID(102)

#define CID_document_DocumentUpdate    DOCUMENT_CID(999)

