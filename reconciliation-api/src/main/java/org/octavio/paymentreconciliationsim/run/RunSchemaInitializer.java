package org.octavio.paymentreconciliationsim.run;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
@Component
public class RunSchemaInitializer implements ApplicationRunner {
 private final MongoTemplate mongo;
 public RunSchemaInitializer(MongoTemplate mongo){this.mongo=mongo;}
 @Override public void run(ApplicationArguments arguments){
  mongo.executeCommand(new Document(mongo.collectionExists("reconciliation_runs")?"collMod":"create","reconciliation_runs")
   .append("validator",Document.parse(VALIDATOR)).append("validationLevel","strict").append("validationAction","error"));
  mongo.executeCommand(new Document("createIndexes","reconciliation_runs").append("indexes",List.of(
   new Document("key",new Document("source",1).append("businessDate",1).append("sha256",1).append("rulesVersion",1)).append("name","unique_run_identity").append("unique",true),
   new Document("key",new Document("businessDate",1).append("createdAt",-1).append("_id",1)).append("name","runs_by_business_date"))));
 }
 private static final String VALIDATOR="""
 {
  "$and": [
    {
      "$jsonSchema": {
        "bsonType": "object",
        "additionalProperties": false,
        "required": [
          "_id",
          "source",
          "businessDate",
          "sha256",
          "rulesVersion",
          "byteLength",
          "objectKey",
          "status",
          "objectIdentity",
          "error",
          "report",
          "createdAt",
          "updatedAt"
        ],
        "properties": {
          "_id": {
            "bsonType": "string",
            "pattern": "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"
          },
          "source": {
            "enum": [
              "SIMULATED"
            ]
          },
          "businessDate": {
            "bsonType": "string",
            "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
          },
          "sha256": {
            "bsonType": "string",
            "pattern": "^[a-f0-9]{64}$"
          },
          "rulesVersion": {
            "enum": [
              "v1"
            ]
          },
          "byteLength": {
            "bsonType": "long",
            "minimum": 1,
            "maximum": 2097152
          },
          "objectKey": {
            "bsonType": "string",
            "minLength": 1
          },
          "status": {
            "enum": [
              "AWAITING_UPLOAD",
              "PROCESSING",
              "COMPLETED",
              "FAILED"
            ]
          },
          "objectIdentity": {
            "bsonType": [
              "object",
              "null"
            ],
            "additionalProperties": false,
            "required": [
              "bucket",
              "key",
              "versionId",
              "sha256"
            ],
            "properties": {
              "bucket": {
                "bsonType": "string",
                "minLength": 1
              },
              "key": {
                "bsonType": "string",
                "minLength": 1
              },
              "versionId": {
                "bsonType": "string",
                "minLength": 1
              },
              "sha256": {
                "bsonType": "string",
                "pattern": "^[a-f0-9]{64}$"
              },
              "_class": {
                "bsonType": "string"
              }
            }
          },
          "error": {
            "bsonType": [
              "object",
              "null"
            ],
            "additionalProperties": false,
            "required": [
              "code",
              "retriable",
              "attemptId"
            ],
            "properties": {
              "code": {
                "bsonType": "string",
                "pattern": "^[A-Z0-9_]{1,64}$"
              },
              "retriable": {
                "bsonType": "bool"
              },
              "attemptId": {
                "bsonType": "string",
                "minLength": 1,
                "maxLength": 64
              },
              "_class": {
                "bsonType": "string"
              }
            }
          },
          "report": {
            "bsonType": [
              "object",
              "null"
            ],
            "additionalProperties": false,
            "required": [
              "inputIdentity",
              "summary",
              "results"
            ],
            "properties": {
              "inputIdentity": {
                "bsonType": "object",
                "additionalProperties": false,
                "required": [
                  "source",
                  "businessDate",
                  "sha256",
                  "rulesVersion",
                  "objectIdentity"
                ],
                "properties": {
                  "source": {
                    "enum": [
                      "SIMULATED"
                    ]
                  },
                  "businessDate": {
                    "bsonType": "string",
                    "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
                  },
                  "sha256": {
                    "bsonType": "string",
                    "pattern": "^[a-f0-9]{64}$"
                  },
                  "rulesVersion": {
                    "enum": [
                      "v1"
                    ]
                  },
                  "objectIdentity": {
                    "bsonType": "object",
                    "additionalProperties": false,
                    "required": [
                      "bucket",
                      "key",
                      "versionId",
                      "sha256"
                    ],
                    "properties": {
                      "bucket": {
                        "bsonType": "string",
                        "minLength": 1
                      },
                      "key": {
                        "bsonType": "string",
                        "minLength": 1
                      },
                      "versionId": {
                        "bsonType": "string",
                        "minLength": 1
                      },
                      "sha256": {
                        "bsonType": "string",
                        "pattern": "^[a-f0-9]{64}$"
                      },
                      "_class": {
                        "bsonType": "string"
                      }
                    }
                  },
                  "_class": {
                    "bsonType": "string"
                  }
                }
              },
              "summary": {
                "bsonType": "object",
                "additionalProperties": false,
                "required": [
                  "internalPurchaseCount",
                  "settlementRowCount",
                  "distinctSettlementReferenceCount",
                  "totalResultCount",
                  "outcomeCounts"
                ],
                "properties": {
                  "internalPurchaseCount": {
                    "bsonType": "int",
                    "minimum": 0,
                    "maximum": 1000
                  },
                  "settlementRowCount": {
                    "bsonType": "int",
                    "minimum": 0,
                    "maximum": 2000
                  },
                  "distinctSettlementReferenceCount": {
                    "bsonType": "int",
                    "minimum": 0,
                    "maximum": 2000
                  },
                  "totalResultCount": {
                    "bsonType": "int",
                    "minimum": 0,
                    "maximum": 3000
                  },
                  "outcomeCounts": {
                    "bsonType": "object",
                    "additionalProperties": false,
                    "required": [
                      "MATCHED",
                      "MISSING_IN_SETTLEMENT",
                      "MISSING_INTERNALLY",
                      "AMOUNT_MISMATCH",
                      "DUPLICATE"
                    ],
                    "properties": {
                      "MATCHED": {
                        "bsonType": "int",
                        "minimum": 0,
                        "maximum": 3000
                      },
                      "MISSING_IN_SETTLEMENT": {
                        "bsonType": "int",
                        "minimum": 0,
                        "maximum": 3000
                      },
                      "MISSING_INTERNALLY": {
                        "bsonType": "int",
                        "minimum": 0,
                        "maximum": 3000
                      },
                      "AMOUNT_MISMATCH": {
                        "bsonType": "int",
                        "minimum": 0,
                        "maximum": 3000
                      },
                      "DUPLICATE": {
                        "bsonType": "int",
                        "minimum": 0,
                        "maximum": 3000
                      },
                      "_class": {
                        "bsonType": "string"
                      }
                    }
                  },
                  "_class": {
                    "bsonType": "string"
                  }
                }
              },
              "results": {
                "bsonType": "array",
                "maxItems": 3000,
                "items": {
                  "bsonType": "object",
                  "additionalProperties": false,
                  "required": [
                    "reference",
                    "outcome",
                    "internalAmountCentavos",
                    "merchantId",
                    "settlementEvidence"
                  ],
                  "properties": {
                    "reference": {
                      "bsonType": "string",
                      "pattern": "^[A-Za-z0-9_-]{1,64}$"
                    },
                    "outcome": {
                      "enum": [
                        "MATCHED",
                        "MISSING_IN_SETTLEMENT",
                        "MISSING_INTERNALLY",
                        "AMOUNT_MISMATCH",
                        "DUPLICATE"
                      ]
                    },
                    "internalAmountCentavos": {
                      "bsonType": [
                        "long",
                        "null"
                      ],
                      "minimum": 1
                    },
                    "merchantId": {
                      "bsonType": [
                        "string",
                        "null"
                      ],
                      "minLength": 1,
                      "maxLength": 64
                    },
                    "settlementEvidence": {
                      "bsonType": "array",
                      "maxItems": 2000,
                      "items": {
                        "bsonType": "object",
                        "additionalProperties": false,
                        "required": [
                          "rowNumber",
                          "reference",
                          "amountCentavos"
                        ],
                        "properties": {
                          "rowNumber": {
                            "bsonType": "int",
                            "minimum": 1,
                            "maximum": 2000
                          },
                          "reference": {
                            "bsonType": "string",
                            "pattern": "^[A-Za-z0-9_-]{1,64}$"
                          },
                          "amountCentavos": {
                            "bsonType": "long",
                            "minimum": 1
                          },
                          "_class": {
                            "bsonType": "string"
                          }
                        }
                      }
                    },
                    "_class": {
                      "bsonType": "string"
                    }
                  }
                }
              },
              "_class": {
                "bsonType": "string"
              }
            }
          },
          "createdAt": {
            "bsonType": "date"
          },
          "updatedAt": {
            "bsonType": "date"
          },
          "_class": {
            "bsonType": "string"
          }
        }
      }
    },
    {
      "$expr": {
        "$and": [
          {
            "$eq": [
              {
                "$dateToString": {
                  "date": {
                    "$dateFromString": {
                      "dateString": "$businessDate",
                      "format": "%Y-%m-%d",
                      "onError": null,
                      "onNull": null
                    }
                  },
                  "format": "%Y-%m-%d",
                  "onNull": null
                }
              },
              "$businessDate"
            ]
          },
          {
            "$or": [
              {
                "$and": [
                  {
                    "$eq": [
                      "$status",
                      "COMPLETED"
                    ]
                  },
                  {
                    "$ne": [
                      "$report",
                      null
                    ]
                  },
                  {
                    "$ne": [
                      "$objectIdentity",
                      null
                    ]
                  },
                  {
                    "$eq": [
                      "$objectIdentity",
                      "$report.inputIdentity.objectIdentity"
                    ]
                  },
                  {
                    "$eq": [
                      "$source",
                      "$report.inputIdentity.source"
                    ]
                  },
                  {
                    "$eq": [
                      "$businessDate",
                      "$report.inputIdentity.businessDate"
                    ]
                  },
                  {
                    "$eq": [
                      "$sha256",
                      "$report.inputIdentity.sha256"
                    ]
                  },
                  {
                    "$eq": [
                      "$rulesVersion",
                      "$report.inputIdentity.rulesVersion"
                    ]
                  },
                  {
                    "$eq": [
                      "$error",
                      null
                    ]
                  }
                ]
              },
              {
                "$and": [
                  {
                    "$ne": [
                      "$status",
                      "COMPLETED"
                    ]
                  },
                  {
                    "$eq": [
                      "$report",
                      null
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    }
  ]
}
 """;
}
