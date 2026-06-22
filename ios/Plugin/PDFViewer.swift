import Foundation
import PDFKit
import UIKit

@objc public class PDFViewer: NSObject {
    private let pdfView = PDFView()
    private let thumbnailView = PDFThumbnailView()
    private let toggleButton = UIButton(type: .system)
    private let buttonVisualEffectView = UIVisualEffectView(
        effect: UIBlurEffect(style: .systemThinMaterialDark))

    // Use a StackView to automatically manage the side-by-side layout and sliding animations
    private let stackView = UIStackView()
    private var pageObserver: NSObjectProtocol?

    @objc public func open(_ pdfURL: URL, top: Int = 0) {
        guard let document = PDFDocument(url: pdfURL) else { return }

        DispatchQueue.main.async {
            // Modern window scene retrieval (keyWindow is deprecated)
            guard
                let windowScene = UIApplication.shared.connectedScenes.first(where: {
                    $0.activationState == .foregroundActive
                }) as? UIWindowScene,
                let window = windowScene.windows.first(where: { $0.isKeyWindow }),
                let rootVC = window.rootViewController
            else { return }

            self.setupHierarchy(in: rootVC.view, topPadding: CGFloat(top))
            self.configureViews(with: document)
        }
    }

    private func setupHierarchy(in parentView: UIView, topPadding: CGFloat) {
        guard !parentView.contains(stackView) else { return }

        // Setup StackView
        stackView.axis = .horizontal
        stackView.alignment = .fill
        stackView.distribution = .fill
        stackView.translatesAutoresizingMaskIntoConstraints = false

        // Add views
        stackView.addArrangedSubview(thumbnailView)
        stackView.addArrangedSubview(pdfView)

        parentView.addSubview(stackView)
        parentView.addSubview(buttonVisualEffectView)

        buttonVisualEffectView.translatesAutoresizingMaskIntoConstraints = false
        thumbnailView.translatesAutoresizingMaskIntoConstraints = false

        // Modern Auto Layout Constraints
        NSLayoutConstraint.activate([
            // StackView fills the screen minus top padding
            stackView.topAnchor.constraint(equalTo: parentView.topAnchor, constant: topPadding),
            stackView.leadingAnchor.constraint(equalTo: parentView.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: parentView.trailingAnchor),
            stackView.bottomAnchor.constraint(equalTo: parentView.bottomAnchor),

            // Sidebar fixed width
            thumbnailView.widthAnchor.constraint(equalToConstant: 120),

            // Floating Button Constraints (Top Left)
            buttonVisualEffectView.leadingAnchor.constraint(
                equalTo: parentView.leadingAnchor, constant: 16),
            buttonVisualEffectView.topAnchor.constraint(
                equalTo: parentView.topAnchor, constant: topPadding + 16),
            buttonVisualEffectView.heightAnchor.constraint(equalToConstant: 44),
        ])
    }

    private func configureViews(with document: PDFDocument) {
        // Configure PDF
        pdfView.document = document
        pdfView.autoScales = true
        pdfView.usePageViewController(true)

        // Configure Thumbnails
        thumbnailView.pdfView = pdfView
        thumbnailView.layoutMode = .vertical
        thumbnailView.backgroundColor = UIColor.systemGroupedBackground
        thumbnailView.isHidden = true  // Hidden by default

        if let firstPage = document.page(at: 0) {
            let bounds = firstPage.bounds(for: .mediaBox)
            let aspectRatio = bounds.height / bounds.width
            thumbnailView.thumbnailSize = CGSize(width: 90, height: 90 * aspectRatio)
        }

        setupGlassButton()

        // Modern Notification Observer
        if let existingObserver = pageObserver {
            NotificationCenter.default.removeObserver(existingObserver)
        }
        pageObserver = NotificationCenter.default.addObserver(
            forName: .PDFViewPageChanged, object: pdfView, queue: .main
        ) { [weak self] _ in
            self?.updateButtonText()
        }
    }

    private func setupGlassButton() {
        buttonVisualEffectView.layer.cornerRadius = 22
        buttonVisualEffectView.clipsToBounds = true

        var config = UIButton.Configuration.plain()
        config.image = UIImage(systemName: "sidebar.left")
        config.imagePadding = 8
        config.baseForegroundColor = .white
        config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16)  // Padding inside button

        toggleButton.configuration = config
        toggleButton.translatesAutoresizingMaskIntoConstraints = false

        // Modern UIAction (No more @objc selectors needed)
        toggleButton.addAction(
            UIAction { [weak self] _ in
                guard let self = self else { return }

                // UIStackView animates structural changes automatically
                UIView.animate(withDuration: 0.3, delay: 0, options: .curveEaseInOut) {
                    self.thumbnailView.isHidden.toggle()
                }
            }, for: .touchUpInside)

        buttonVisualEffectView.contentView.addSubview(toggleButton)

        NSLayoutConstraint.activate([
            toggleButton.topAnchor.constraint(
                equalTo: buttonVisualEffectView.contentView.topAnchor),
            toggleButton.bottomAnchor.constraint(
                equalTo: buttonVisualEffectView.contentView.bottomAnchor),
            toggleButton.leadingAnchor.constraint(
                equalTo: buttonVisualEffectView.contentView.leadingAnchor),
            toggleButton.trailingAnchor.constraint(
                equalTo: buttonVisualEffectView.contentView.trailingAnchor),
        ])

        updateButtonText()
    }

    private func updateButtonText() {
        guard let document = pdfView.document, let currentPage = pdfView.currentPage else { return }
        let total = document.pageCount
        let current = document.index(for: currentPage) + 1
        toggleButton.configuration?.title = "\(current) of \(total)"
    }

    @objc public func closeViewer() {
        DispatchQueue.main.async {
            // Modern window scene retrieval matching open()
            guard
                let windowScene = UIApplication.shared.connectedScenes.first(where: {
                    $0.activationState == .foregroundActive
                }) as? UIWindowScene,
                let window = windowScene.windows.first(where: { $0.isKeyWindow }),
                let rootViewController = window.rootViewController
            else { return }

            // Hide views instantly to make the closing effect feel faster
            rootViewController.view.sendSubviewToBack(self.stackView)
            self.buttonVisualEffectView.isHidden = true

            // Clear document and observer to free up memory
            self.pdfView.document = nil

            // Remove the main container views from the screen
            self.stackView.removeFromSuperview()
            self.buttonVisualEffectView.removeFromSuperview()

            // Reset the thumbnail view state for the next time it opens
            self.thumbnailView.isHidden = true
        }
    }
}
