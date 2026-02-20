# Integrating Structured DeX Content into Elsa

## Overview

This document explores how integrating structured Device Extension (DeX) data into Elsa (an AI system powered by Claude) can dramatically improve the accuracy and reliability of responses to regulatory questions about in vitro diagnostic devices (IVDs).

## Background

- **Elsa** is an FDA tool powered by Claude LLM designed to support regulatory reviewers
- Without structured data, Elsa's performance on device-specific queries is limited, often producing incomplete or incorrect answers (hallucinations)
- The **Device Extension (DeX)** model provides standardized, machine-readable formats for medical device knowledge
- **Komet** is an open-source tool that enables creation and management of this structured DeX content

## Key Finding

The document references a *JAMA Network Open* study showing that when LLMs were given structured, relational data (vs. unstructured text), their performance on medical licensing exams improved by **5.3 to 10 percentage points**. This principle applies directly to FDA device regulation.

## The Three Test Scenarios

### Scenario 1: Single Device, Multiple Questions

- **Device tested:** Roche Diagnostics COBAS Integra Albumin Gen.2 assay
- **Questions asked:** Primary DI, LOD, reference range, units of measure, specimen types, FDA product codes, submission numbers, and full DeX attributes

### Scenario 2: Four Devices, Multiple Questions with Comparison

**Devices tested:**

1. Panther Fusion SARS-CoV-2/Flu A/B/RSV Assay
2. ARIES Flu A/B & RSV Assay
3. Simplexa COVID-19 & Flu A/B Direct
4. GeneXpert Xpert Xpress CoV-2/Flu/RSV plus

**Questions included:** Same technical details as Scenario 1, plus comparative analysis (which has highest RSV sensitivity)

### Scenario 3: Twelve Devices, Complex Single Prompt

All devices from Scenarios 1 & 2, plus:

5. Atellica IM High-Sensitivity Troponin I
6. i-STAT hs-TnI Cartridge
7. Architect Hemoglobin A1c
8. VITROS Chemistry HbA1c products
9. HER2 IQFISH pharmDx Kit
10. Abbott RealTime IDH1
11. UniCel DxH hematology systems

**Task:** Populate complete DeX attributes for all 12 devices in tabular format

## Results Comparison

### Without Structured DeX Data

| System | Outcome |
|--------|---------|
| **Elsa** | Explicitly declined to provide answers, stating it lacked access to necessary databases |
| **Claude Sonnet 4.0** | Attempted responses with heavy caveats, but accuracy could not be relied upon; provided incomplete information with many "not specified" entries |

### With Structured DeX Data

- **Elsa** provided consistently accurate and complete responses
- Successfully extracted information from large spreadsheets (27-180 lines for 12 devices)
- Answered multiple questions for multiple devices concurrently
- Demonstrated ability to handle nuanced comparative questions (e.g., determining highest sensitivity)

## Key Capabilities Demonstrated

| Capability | Detail |
|------------|--------|
| **Accuracy** | Complete elimination of hallucinations when structured data provided |
| **Scalability** | Single spreadsheet containing all 12 IVDs was sufficient |
| **Concurrent processing** | Multiple questions across multiple devices answered simultaneously |
| **Nuanced analysis** | Comparative assessments (e.g., sensitivity comparisons) performed correctly |
| **Source attribution** | All responses included specific source citations |

## Conclusion

The document demonstrates that providing LLMs with structured, authoritative device data through the DeX model dramatically improves regulatory decision-support capabilities, potentially accelerating review processes while maintaining accuracy and reliability.
